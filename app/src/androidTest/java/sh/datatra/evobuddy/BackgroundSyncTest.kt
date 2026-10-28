package sh.datatra.evobuddy

import android.app.job.JobScheduler
import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.time.Instant

/** Offline emulator checks; no credentials or heating commands. */
class BackgroundSyncTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("evobuddy", Context.MODE_PRIVATE)
    private val now = Instant.parse("2026-11-13T14:30:00Z")
    private val snapshot = demoSnapshot().copy(updated = now.minusSeconds(3600))
    private var calls = 0
    private fun session() = JSONObject().put("access_token", "offline").put("refresh_token", "offline").put("expiresAt", "2099-01-01T00:00:00Z")
    @Before fun prepare() {
        assumeTrue(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        BackgroundSync.cancel(context)
        BackgroundSync.client(context).clearSession()
        TokenVault(context).clear()
        prefs.edit().clear().putString("cache", snapshot.json().toString()).putString("shortcuts", "keep").commit()
    }
    @After fun cleanup() {
        BackgroundSync.cancel(context); BackgroundSync.client(context).clearSession()
        TokenVault(context).clear(); prefs.edit().clear().commit()
    }
    private fun fake(onRead: () -> Unit = {}): TccClient = TccClient(Transport { method, path, _, _ ->
        calls++
        assertEquals("GET", method); assertTrue(path.contains("/status?"))
        onRead()
        val home = snapshot.homes.first()
        val zones = JSONArray(home.rooms.map { JSONObject().put("zoneId", it.id)
            .put("temperatureStatus", JSONObject().put("temperature", 19.5).put("isAvailable", true))
            .put("setpointStatus", JSONObject().put("targetHeatTemperature", 20).put("setpointMode", "FollowSchedule")) })
        val system = JSONObject().put("systemId", home.systemId).put("zones", zones)
        HttpResult(200, JSONObject().put("gateways", JSONArray().put(JSONObject().put("temperatureControlSystems", JSONArray().put(system)))).toString())
    }).apply { session = session() }
    @Test fun statusReadUpdatesOnlyCacheAndIsLoadedOnResume() {
        val model = AppState(context.applicationContext as android.app.Application)
        assertTrue(runBlocking { BackgroundSync.syncOnce(context, fake()) { now } })
        val saved = snapshotFromJson(JSONObject(prefs.getString("cache", "")!!))
        assertEquals(now, saved.updated)
        assertEquals(19.5, saved.homes.first().rooms.first().measured!!, 0.0)
        assertEquals(snapshot.homes.first().rooms.first().schedule, saved.homes.first().rooms.first().schedule)
        assertEquals("keep", prefs.getString("shortcuts", "")); assertEquals(1, calls)
        model.reloadBackgroundCache()
        assertEquals(now, model.state.value.snapshot!!.updated)
    }
    @Test fun recentCacheAndDemoSkipNetwork() {
        prefs.edit().putString("cache", snapshot.copy(updated = now.minusSeconds(30)).json().toString()).commit()
        assertFalse(runBlocking { BackgroundSync.syncOnce(context, fake()) { now } })
        prefs.edit().putBoolean("demo", true).putString("cache", snapshot.json().toString()).commit()
        assertFalse(runBlocking { BackgroundSync.syncOnce(context, fake()) { now } })
        assertEquals(0, calls)
    }
    @Test fun aNewerUserCacheCannotBeOverwritten() {
        val newer = snapshot.copy(updated = now.plusSeconds(1))
        assertFalse(runBlocking { BackgroundSync.syncOnce(context, fake { BackgroundSync.storeCache(context, newer) }) { now } })
        assertEquals(newer.updated, snapshotFromJson(JSONObject(prefs.getString("cache", "")!!)).updated)
    }
    @Test fun failedReadPreservesLastGoodCache() {
        val before = prefs.getString("cache", "")
        val client = TccClient(Transport { _, _, _, _ -> throw java.io.IOException("Offline") }).apply { session = session() }
        assertTrue(runCatching { runBlocking { BackgroundSync.syncOnce(context, client) { now } } }.isFailure)
        assertEquals(before, prefs.getString("cache", ""))
    }
    @Test fun jobHasNetworkBatteryAndPersistenceConstraintsAndCancelsForDemo() {
        BackgroundSync.client(context).session = session()
        BackgroundSync.ensureScheduled(context)
        BackgroundSync.ensureScheduled(context)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = scheduler.getPendingJob(BackgroundSync.JOB_ID)!!
        assertEquals(900000L, job.intervalMillis)
        assertTrue(job.isPersisted); assertTrue(job.isRequireBatteryNotLow)
        assertNotNull(job.requiredNetwork)
        assertEquals(1, scheduler.allPendingJobs.count { it.id == BackgroundSync.JOB_ID })
        prefs.edit().putBoolean("demo", true).commit()
        BackgroundSync.ensureScheduled(context)
        assertNull(scheduler.getPendingJob(BackgroundSync.JOB_ID))
    }
}
