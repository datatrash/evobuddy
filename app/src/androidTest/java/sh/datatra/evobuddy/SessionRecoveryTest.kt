package sh.datatra.evobuddy

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SessionRecoveryTest {
    @Test fun rejectedLoginPreservesDataAndOriginalAccountCanReconnect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        assumeTrue(application.packageName.endsWith(".sessiontest") || Build.FINGERPRINT.contains("generic") || Build.MODEL.startsWith("sdk_gphone"))
        val prefs = application.getSharedPreferences("evobuddy", Context.MODE_PRIVATE)
        val client = BackgroundSync.client(application)
        val snapshot = demoSnapshot()
        val home = snapshot.homes.single()
        val room = home.rooms.first()
        val shortcut = Shortcut("saved", "Warm up", home.key, listOf(room.id), 20.0, 30)
        val shortcuts = JSONArray().put(shortcut.json()).toString()
        val backupKey = "backup:${home.key}:${room.id}"
        val backup = room.schedule!!.json().toString()
        val order = """{"${home.key}":["${room.id}"]}"""
        val favourites = setOf("${home.key}:${room.id}")
        var account = "different"
        val loginClient = TccClient(Transport { method, path, _, _ ->
            when {
                method == "POST" -> HttpResult(200, """{"access_token":"access","refresh_token":"refresh","expires_in":1800}""")
                path.endsWith("/userAccount") -> HttpResult(200, """{"userId":"$account"}""")
                path.contains("/installationInfo?") -> {
                    val location = if (account == "original") home.locationId else "different-location"
                    HttpResult(200, """
                        [{"locationInfo":{"locationId":"$location","timeZone":{"timeZoneId":"UTC"}},
                          "gateways":[{"temperatureControlSystems":[{"systemId":"${home.systemId}","zones":[]}]}]}]
                    """.trimIndent())
                }
                path.contains("/status?") -> HttpResult(200, """{"gateways":[{"temperatureControlSystems":[{"systemId":"${home.systemId}","zones":[]}]}]}""")
                else -> HttpResult(200, backup)
            }
        })

        BackgroundSync.cancel(application)
        client.clearSession()
        TokenVault(application).clear()
        prefs.edit().clear().putString("cache", snapshot.json().toString())
            .putString("shortcuts", shortcuts).putString("theme", "Dark").putString("home", home.key)
            .putStringSet("favourites", favourites).putString("roomOrder", order).putString(backupKey, backup).commit()
        lateinit var model: AppState
        instrumentation.runOnMainSync { model = AppState(application, loginClient) }
        try {
            assertTrue(model.state.value.reconnecting)
            instrumentation.runOnMainSync { model.login("user@example.test", "test-password") }
            runBlocking { withTimeout(5_000) { model.state.first { it.error && !it.busy } } }
            assertTrue(model.state.value.reconnecting)
            assertNull(client.session)
            assertEquals(shortcuts, prefs.getString("shortcuts", null))

            account = "original"
            instrumentation.runOnMainSync { model.login("user@example.test", "test-password") }
            val state = runBlocking { withTimeout(5_000) { model.state.first { it.connected && !it.busy } } }
            assertFalse(state.reconnecting)
            assertEquals(listOf(shortcut), state.shortcuts)
            assertEquals("Dark", state.theme)
            assertEquals(home.key, state.homeKey)
            assertEquals("original", prefs.getString("tccAccountId", null))
            assertEquals(favourites, state.favourites)
            assertEquals(order, prefs.getString("roomOrder", null))
            assertEquals(backup, prefs.getString(backupKey, null))
        } finally {
            instrumentation.runOnMainSync { model.viewModelScope.cancel(); model.logout() }
            prefs.edit().clear().commit()
        }
    }
}
