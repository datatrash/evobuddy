package sh.datatra.evobuddy

import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.time.Instant

/** Offline outbox integration: never connects to a real TCC account. */
class CommandsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("evobuddy", Context.MODE_PRIVATE)
    private val now = Instant.now()
    private var clock = now
    private val home = demoSnapshot().homes.first()
    private val actual = home.rooms.associateBy { it.id }.toMutableMap()
    private val writes = mutableListOf<Pair<String, JSONObject>>()
    private var onPut: (String, JSONObject) -> Unit = { _, _ -> }
    private var onGet: () -> Unit = {}
    private var getCode = 200
    private fun status(): String {
        val zones = JSONArray(actual.values.map { room -> JSONObject().put("zoneId", room.id)
            .put("temperatureStatus", JSONObject().put("temperature", 19.5).put("isAvailable", true))
            .put("setpointStatus", JSONObject().put("targetHeatTemperature", room.target).put("setpointMode", room.mode).put("until", room.until?.toString())) })
        return JSONObject().put("gateways", JSONArray().put(JSONObject().put("temperatureControlSystems", JSONArray().put(JSONObject().put("systemId", home.systemId).put("zones", zones))))).toString()
    }
    private fun client() = TccClient(Transport { method, path, _, body ->
        when (method) {
            "GET" -> { assertTrue(path.contains("/status?")); onGet(); HttpResult(getCode, status()) }
            "PUT" -> {
                val id = path.substringAfter("temperatureZone/").substringBefore('/')
                val json = JSONObject(body!!); writes += id to json; onPut(id, json)
                HttpResult(200, "{}") // Missing commTaskId is deliberately not treated as a failure.
            }
            else -> HttpResult(401, "{}")
        }
    }).apply { session = JSONObject().put("access_token", "offline").put("refresh_token", "offline").put("expiresAt", "2099-01-01T00:00:00Z") }
    private fun apply(id: String, body: JSONObject) {
        actual[id] = actual.getValue(id).copy(target = body.numberOrNull("heatSetpointValue"), mode = body.getString("setpointMode"), until = body.optString("timeUntil").toInstantOrNull())
    }
    private fun enqueue(rooms: List<Room> = home.rooms.take(2), temperature: Double = 20.0, until: Instant = now.plusSeconds(3600)) =
        Commands.enqueue(context, home, rooms, temperature, until, source = "shortcut", now = clock)
    private fun run(client: TccClient) = runBlocking { Commands.runDue(context, client) { clock } }
    @Before fun setup() {
        assumeTrue(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        Commands.clear(context); BackgroundSync.cancel(context); BackgroundSync.client(context).clearSession(); TokenVault(context).clear()
        prefs.edit().clear().putString("cache", demoSnapshot().copy(updated = now).json().toString()).commit()
    }
    @After fun cleanup() { Commands.clear(context); prefs.edit().clear().commit() }
    @Test fun lostWriteResponseIsReconciledWithoutDuplicateWritesOrExtendedExpiry() {
        val client = client()
        onPut = { id, body -> apply(id, body); if (id == "living") throw java.io.IOException("Response lost") }
        enqueue(); val persisted = prefs.getString("pendingChanges", "")
        assertEquals(2, JSONArray(persisted).length())
        run(client)
        assertEquals(2, writes.size)
        clock = now.plusSeconds(15); run(client)
        assertTrue(Commands.pending(context).isEmpty()); assertEquals(2, writes.size)
        assertTrue(writes.all { it.second.getString("timeUntil") == now.plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString() })
        assertTrue(Commands.issues(context).isEmpty())
        assertEquals(19.5, snapshotFromJson(JSONObject(prefs.getString("cache", "")!!)).homes.first().rooms.first().measured!!, .0)
    }
    @Test fun failedRoomRetriesIndependentlyAndKeepsTheOriginalEndTime() {
        val client = client(); var livingAttempts = 0
        onPut = { id, body -> if (id == "living" && livingAttempts++ == 0) throw java.io.IOException("Offline") else apply(id, body) }
        enqueue(); run(client)
        clock = now.plusSeconds(3); run(client)
        assertEquals(listOf("living"), Commands.pending(context).map { it.room })
        clock = now.plusSeconds(21); run(client)
        clock = now.plusSeconds(24); run(client)
        assertTrue(Commands.pending(context).isEmpty())
        assertEquals(2, writes.count { it.first == "living" }); assertEquals(1, writes.count { it.first == "kitchen" })
        assertEquals(1, writes.map { it.second.getString("timeUntil") }.distinct().size)
    }
    @Test fun newerIntentWinsEvenWhenTheOlderWriteWasAlreadyInFlight() {
        val client = client(); val room = home.rooms.first()
        enqueue(listOf(room), 19.0); enqueue(listOf(room), 22.0)
        onPut = { id, body ->
            apply(id, body)
            if (body.getDouble("heatSetpointValue") == 22.0) enqueue(listOf(room), 23.0)
        }
        run(client)
        assertEquals(23.0, Commands.pending(context).single().temperature!!, .0)
        clock = now.plusSeconds(3); run(client)
        clock = now.plusSeconds(6); run(client)
        assertTrue(Commands.pending(context).isEmpty())
        assertEquals(listOf(22.0, 23.0), writes.map { it.second.getDouble("heatSetpointValue") })
    }
    @Test fun expiredChangesAndLogoutCannotBeReplayed() {
        val client = client()
        enqueue(until = now.plusSeconds(10)); clock = now.plusSeconds(15); run(client)
        assertTrue(Commands.pending(context).isEmpty()); assertTrue(writes.isEmpty())
        enqueue(); Commands.clear(context); run(client)
        assertTrue(Commands.pending(context).isEmpty()); assertTrue(writes.isEmpty())
    }
    @Test fun rateLimitsBackOffWithoutDiscardingTheChange() {
        val client = client(); getCode = 429
        enqueue(); run(client)
        assertEquals(2, Commands.pending(context).size)
        assertTrue(Commands.pending(context).all { it.next >= now.plusSeconds(60) })
        assertTrue(writes.isEmpty()); assertTrue(Commands.issues(context).isEmpty())
    }
    @Test fun alreadyAppliedSettingsDoNotProduceAnotherWrite() {
        val client = client(); val room = home.rooms.first()
        enqueue(listOf(room))
        actual[room.id] = room.copy(target = 20.0, mode = "TemporaryOverride", until = now.plusSeconds(3600))
        run(client)
        assertTrue(Commands.pending(context).isEmpty()); assertTrue(writes.isEmpty())
    }
    @Test fun logoutDuringAReadCannotResurrectCommandsOrWriteAfterDisconnect() {
        val client = client(); enqueue()
        onGet = { Commands.clear(context) }
        assertTrue(runCatching { run(client) }.isFailure)
        assertTrue(Commands.pending(context).isEmpty()); assertTrue(writes.isEmpty())
    }
}
