package sh.datatra.evobuddy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TccClientTest {
    data class Call(val method: String, val path: String, val headers: Map<String, String>, val body: String?)
    private class Fake(vararg val responses: HttpResult) : Transport {
        val calls = mutableListOf<Call>()
        override fun send(method: String, path: String, headers: Map<String, String>, body: String?): HttpResult {
            calls += Call(method, path, headers, body)
            return responses[calls.lastIndex]
        }
    }
    private fun session() = JSONObject().put("access_token", "test-access").put("refresh_token", "test-refresh").put("expiresAt", Instant.now().plusSeconds(3600).toString())
    private val tokenResponse = HttpResult(200, """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":1800}""")
    @Test fun `login uses European endpoint and case-sensitive form fields`() {
        val fake = Fake(tokenResponse)
        var saved: JSONObject? = null
        val client = TccClient(fake) { saved = it }
        client.login("person@example.com", "secret&value")
        assertEquals("Auth/OAuth/Token", fake.calls.single().path)
        assertTrue(fake.calls.single().body!!.contains("Username=person%40example.com"))
        assertTrue(fake.calls.single().body!!.contains("Password=secret%26value"))
        assertFalse(saved!!.toString().contains("secret"))
    }
    @Test fun `expired access token refreshes without retaining password`() {
        val fake = Fake(tokenResponse, HttpResult(200, "{}"))
        val client = TccClient(fake).apply { session = session().put("expiresAt", Instant.EPOCH.toString()) }
        client.request("GET", "userAccount")
        assertTrue(fake.calls[0].body!!.contains("grant_type=refresh_token"))
        assertFalse(fake.calls[0].body!!.contains("Password"))
        assertEquals("bearer new-access", fake.calls[1].headers["Authorization"])
    }
    @Test fun `rejected authorization refreshes only once`() {
        val fake = Fake(HttpResult(401, "private detail"), tokenResponse, HttpResult(401, "private detail"))
        val client = TccClient(fake).apply { session = session() }
        val error = assertThrows(CloudException::class.java) { client.request("GET", "userAccount") }
        assertEquals(3, fake.calls.size)
        assertFalse(error.message!!.contains("private detail"))
        assertTrue(client.sessionRejected.value)
        assertNull(client.session)
    }
    @Test fun `revoked refresh token is removed and is never retried`() {
        val fake = Fake(HttpResult(400, "{\"error\":\"invalid_grant\"}"))
        var removed = false
        val client = TccClient(fake, removeToken = { removed = true }).apply {
            session = session().put("expiresAt", Instant.EPOCH.toString())
        }
        assertEquals(401, assertThrows(CloudException::class.java) { client.request("GET", "userAccount") }.status)
        assertTrue(removed)
        assertTrue(client.sessionRejected.value)
        assertThrows(CloudException::class.java) { client.request("GET", "userAccount") }
        assertEquals(1, fake.calls.size)
    }
    @Test fun `temporary refresh failure keeps the current session`() {
        for (status in listOf(429, 503)) {
            val fake = Fake(HttpResult(status, ""))
            val client = TccClient(fake, removeToken = { fail("A temporary failure must retain the token") }).apply {
                session = session().put("expiresAt", Instant.EPOCH.toString())
            }
            assertEquals(status, assertThrows(CloudException::class.java) { client.request("GET", "userAccount") }.status)
            assertFalse(client.sessionRejected.value)
            assertEquals("test-refresh", client.session!!.getString("refresh_token"))
        }
    }
    @Test fun `refresh can replace the access token while retaining a refresh token`() {
        val fake = Fake(HttpResult(200, """{"access_token":"new-access","expires_in":1800}"""), HttpResult(200, "{}"))
        val client = TccClient(fake).apply { session = session().put("expiresAt", Instant.EPOCH.toString()) }
        client.request("GET", "userAccount")
        assertEquals("test-refresh", client.session!!.getString("refresh_token"))
        assertEquals("bearer new-access", fake.calls[1].headers["Authorization"])
    }
    @Test fun `concurrent requests rejected for the same token share a refresh`() {
        val rejectedRequests = CyclicBarrier(2)
        val refreshes = AtomicInteger()
        val client = TccClient(Transport { method, _, headers, _ ->
            when {
                method == "POST" -> { refreshes.incrementAndGet(); tokenResponse }
                headers["Authorization"] == "bearer test-access" -> {
                    rejectedRequests.await(5, TimeUnit.SECONDS)
                    HttpResult(401, "")
                }
                else -> HttpResult(200, "{}")
            }
        }).apply { session = session() }
        val worker = Executors.newFixedThreadPool(2)
        try {
            val requests = List(2) { worker.submit<String> { client.request("GET", "userAccount") } }
            requests.forEach { assertEquals("{}", it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, refreshes.get())
        } finally { worker.shutdownNow() }
    }
    @Test fun `timed overrides include UTC expiry and expected vendor keys`() {
        val fake = Fake(HttpResult(200, "{\"id\":\"123\"}"))
        val client = TccClient(fake).apply { session = session() }
        val end = Instant.now().plusSeconds(3600)
        client.override(Room("456", "Office"), 20.5, end)
        val request = fake.calls.single()
        val body = JSONObject(request.body!!)
        assertEquals("PUT", request.method)
        assertEquals("WebAPI/emea/api/v1/temperatureZone/456/heatSetpoint", request.path)
        assertEquals("TemporaryOverride", body.getString("setpointMode"))
        assertEquals(end.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString(), body.getString("timeUntil"))
        assertEquals(20.5, body.getDouble("heatSetpointValue"), 0.0)
    }
    @Test fun `resume does not accidentally send an expiry or temperature`() {
        val fake = Fake(HttpResult(200, "{}"))
        TccClient(fake).apply { session = session() }.override(Room("1", "Office"), null, null, true)
        val body = JSONObject(fake.calls.single().body!!)
        assertEquals(setOf("setpointMode"), body.keys().asSequence().toSet())
        assertEquals("FollowSchedule", body.getString("setpointMode"))
    }
    @Test fun `invalid writes never reach network`() {
        val fake = Fake()
        val client = TccClient(fake).apply { session = session() }
        assertThrows(IllegalArgumentException::class.java) { client.override(Room("1", "Office"), 100.0, null) }
        assertThrows(IllegalArgumentException::class.java) { client.override(Room("1", "Office"), 20.0, Instant.EPOCH) }
        assertTrue(fake.calls.isEmpty())
    }
    @Test fun `write timeout does not cause duplicate heating commands`() {
        var calls = 0
        val client = TccClient(Transport { _, _, _, _ -> calls++; throw IOException("timeout") }).apply { session = session() }
        assertThrows(IOException::class.java) { client.override(Room("1", "Office"), 20.0, null) }
        assertEquals(1, calls)
    }
    @Test fun `server errors do not retry writes or expose response bodies`() {
        val fake = Fake(HttpResult(503, "sensitive response"))
        val client = TccClient(fake).apply { session = session() }
        val error = assertThrows(CloudException::class.java) { client.override(Room("1", "Office"), 20.0, null) }
        assertEquals(1, fake.calls.size)
        assertFalse(error.message!!.contains("sensitive"))
    }
    @Test fun `rate limiting prevents immediate repeat requests`() {
        val fake = Fake(HttpResult(429, ""))
        val client = TccClient(fake).apply { session = session() }
        assertThrows(CloudException::class.java) { client.request("GET", "userAccount") }
        assertThrows(CloudException::class.java) { client.request("GET", "userAccount") }
        assertEquals(1, fake.calls.size)
    }
}
