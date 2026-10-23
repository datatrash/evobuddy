package sh.datatra.evobuddy

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

fun needsBackgroundRefresh(updated: Instant, now: Instant): Boolean {
    val age = Duration.between(updated, now).seconds
    return age < 0 || age >= 15 * 60
}

/** One token owner; background cache writes cannot overtake user actions. */
object BackgroundSync {
    const val JOB_ID = 1401
    const val INTERVAL_MS = 15 * 60 * 1000L
    private val monitor = Any()
    private var sharedClient: TccClient? = null
    private var foregroundCount = 0
    private var operations = 0
    private var epoch = 0L
    private var backgroundTask: Job? = null

    fun client(context: Context): TccClient = synchronized(monitor) {
        sharedClient ?: TokenVault(context.applicationContext).let { vault ->
            TccClient(saveToken = vault::save).apply { session = vault.read() }
        }.also { sharedClient = it }
    }
    private fun invalidate(change: () -> Unit = {}) {
        val task = synchronized(monitor) { change(); epoch++; backgroundTask }
        task?.cancel()
    }
    fun enterForeground() = invalidate { foregroundCount++ }
    fun leaveForeground(context: Context) {
        synchronized(monitor) { foregroundCount = (foregroundCount - 1).coerceAtLeast(0) }
        ensureScheduled(context)
    }
    fun beginOperation() = invalidate { operations++ }
    fun endOperation() { synchronized(monitor) { operations = (operations - 1).coerceAtLeast(0) } }
    fun cancel(context: Context) {
        invalidate()
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
    fun storeCache(context: Context, snapshot: Snapshot) {
        synchronized(monitor) {
            epoch++
            context.getSharedPreferences("evobuddy", Context.MODE_PRIVATE).edit().putString("cache", snapshot.json().toString()).apply()
        }
        HomeActions.updateRoomWidgets(context)
    }
    fun cacheStatus(context: Context, homeKey: String, status: JSONObject, now: Instant) {
        synchronized(monitor) {
            val prefs = context.getSharedPreferences("evobuddy", Context.MODE_PRIVATE)
            val snapshot = runCatching { snapshotFromJson(JSONObject(prefs.getString("cache", "")!!)) }.getOrNull() ?: return
            epoch++
            prefs.edit().putString("cache", snapshot.copy(homes = snapshot.homes.map { if (it.key == homeKey) applyStatus(it, status) else it }, updated = now).json().toString()).commit()
        }
        HomeActions.updateRoomWidgets(context)
    }
    fun ensureScheduled(context: Context) {
        val prefs = context.getSharedPreferences("evobuddy", Context.MODE_PRIVATE)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (prefs.getBoolean("demo", false) || client(context).session == null || !prefs.contains("cache")) {
            scheduler.cancel(JOB_ID)
            return
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, CacheSyncService::class.java))
            .setPeriodic(INTERVAL_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .build())
    }

    /** Status GETs only. Client and clock injection allow offline transport tests. */
    suspend fun syncOnce(context: Context, client: TccClient = client(context), clock: () -> Instant = Instant::now): Boolean {
        val work = currentCoroutineContext().job
        return withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("evobuddy", Context.MODE_PRIVATE)
            val source: String
            val ticket: Long
            synchronized(monitor) {
                if (foregroundCount > 0 || operations > 0 || prefs.getBoolean("demo", false) || client.session == null) return@withContext false
                source = prefs.getString("cache", null) ?: return@withContext false
                ticket = epoch
                backgroundTask = work
            }
            try {
                val snapshot = snapshotFromJson(JSONObject(source))
                if (snapshot.homes.isEmpty() || !needsBackgroundRefresh(snapshot.updated, clock())) return@withContext false
                val statuses = snapshot.homes.distinctBy { it.locationId }.associate { home ->
                    ensureActive()
                    home.locationId to client.status(home)
                }
                ensureActive()
                val refreshed = snapshot.copy(homes = snapshot.homes.map { applyStatus(it, statuses.getValue(it.locationId)) }, updated = clock())
                val saved = synchronized(monitor) {
                    if (!work.isActive || epoch != ticket || foregroundCount > 0 || operations > 0 ||
                        prefs.getBoolean("demo", false) || client.session == null || prefs.getString("cache", null) != source) return@synchronized false
                    // Durable before job completion. Other preferences are untouched.
                    prefs.edit().putString("cache", refreshed.json().toString()).commit()
                }
                if (saved) HomeActions.updateRoomWidgets(context)
                saved
            } finally {
                synchronized(monitor) { if (backgroundTask === work) backgroundTask = null }
            }
        }
    }
}

class EvoBuddyApplication : Application() {
    override fun onCreate() { super.onCreate(); BackgroundSync.ensureScheduled(this); Commands.schedule(this) }
}

/** No foreground mode, notifications or third-party scheduling library. */
class CacheSyncService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null
    private var runId = 0
    override fun onStartJob(params: JobParameters): Boolean {
        val id = ++runId
        running?.cancel()
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try { withTimeout(90_000) { BackgroundSync.syncOnce(applicationContext) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep last good cache; wait for next periodic run. */ }
            finally { if (runId == id) { running = null; jobFinished(params, false) } }
        }
        running = task
        task.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        runId++; running?.cancel(); running = null
        return false
    }
    override fun onDestroy() { runId++; scope.cancel(); super.onDestroy() }
}
