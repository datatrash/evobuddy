package sh.datatra.evobuddy

import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.time.*
import java.util.UUID
import kotlin.math.abs

data class RoomCommand(
    val id: String, val home: String, val room: String, val temperature: Double?, val until: Instant?,
    val resume: Boolean, val deadline: Instant, val next: Instant, val source: String? = null,
    val attempts: Int = 0, val lastWrite: Instant? = null
) {
    fun matches(actual: Room): Boolean = actual.mode == (if (resume) "FollowSchedule" else if (until == null) "PermanentOverride" else "TemporaryOverride") &&
        (resume || actual.target?.let { abs(it - temperature!!) < .05 } == true) &&
        (until == null || actual.until?.let { abs(Duration.between(until, it).seconds) <= 60 } == true)
    fun json() = JSONObject().put("id", id).put("home", home).put("room", room).put("temperature", temperature)
        .put("until", until?.toString()).put("resume", resume).put("deadline", deadline.toString())
        .put("next", next.toString()).put("source", source).put("attempts", attempts).put("lastWrite", lastWrite?.toString())
    companion object {
        fun parse(j: JSONObject) = RoomCommand(j.getString("id"), j.getString("home"), j.getString("room"), j.numberOrNull("temperature"),
            j.optString("until").toInstantOrNull(), j.getBoolean("resume"), Instant.parse(j.getString("deadline")),
            Instant.parse(j.getString("next")), j.optString("source").takeIf { it.isNotBlank() }, j.optInt("attempts"), j.optString("lastWrite").toInstantOrNull())
    }
}

fun commandRetrySeconds(attempt: Int, rateLimited: Boolean = false): Long =
    maxOf(if (rateLimited) 60L else 0L, listOf(3L, 10L, 30L, 60L, 120L, 300L)[attempt.coerceIn(0, 5)])
fun commandDeadline(now: Instant, until: Instant?): Instant = minOf(until ?: now.plusSeconds(300), now.plusSeconds(300))

/** Durable, latest-action-per-room outbox. A missing acknowledgement is not a failure. */
object Commands {
    const val JOB_ID = 1402
    private val lock = Any()
    private val processor = Mutex()
    private var processing: Job? = null
    @Volatile var foreground = false
    private fun prefs(context: Context) = context.getSharedPreferences("evobuddy", Context.MODE_PRIVATE)
    fun pending(context: Context): List<RoomCommand> = synchronized(lock) {
        runCatching { JSONArray(prefs(context).getString("pendingChanges", "[]")).objects().map(RoomCommand::parse) }.getOrDefault(emptyList())
    }
    fun issues(context: Context): Map<String, String> = runCatching {
        JSONObject(prefs(context).getString("commandIssues", "{}")!!).let { j -> j.keys().asSequence().associateWith { j.getString(it) } }
    }.getOrDefault(emptyMap())
    private fun store(context: Context, commands: List<RoomCommand>) {
        check(prefs(context).edit().putString("pendingChanges", JSONArray(commands.map { it.json() }).toString()).commit())
    }
    private fun issue(context: Context, source: String?, message: String?) {
        if (source == null) return
        val updated = issues(context).toMutableMap().apply { if (message == null) remove(source) else put(source, message) }
        prefs(context).edit().putString("commandIssues", JSONObject(updated as Map<*, *>).toString()).commit()
    }
    fun clearIssues(context: Context) = synchronized(lock) { prefs(context).edit().remove("commandIssues").commit() }
    fun enqueue(context: Context, home: Home, rooms: List<Room>, temperature: Double?, until: Instant?, resume: Boolean = false, source: String? = null, now: Instant = Instant.now()) {
        require(until == null || until > now)
        require(rooms.isNotEmpty() && rooms.all { (if (resume) "FollowSchedule" else if (until == null) "PermanentOverride" else "TemporaryOverride") in it.allowed && (resume || temperature != null && it.validTemperature(temperature)) })
        val end = until?.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val deadline = commandDeadline(now, end)
        synchronized(lock) {
            val ids = rooms.map { it.id }.toSet()
            val kept = pending(context).filterNot { it.home == home.key && it.room in ids }
            issue(context, source, null)
            store(context, kept + rooms.map { RoomCommand(UUID.randomUUID().toString(), home.key, it.id, temperature, end, resume, deadline, now, source) })
        }
        HomeActions.updateWidgets(context)
        schedule(context)
    }
    fun enqueueShortcut(context: Context, shortcut: Shortcut) {
        val (homes, saved) = HomeActions.catalog(context)
        val current = saved.find { it.id == shortcut.id && it.homeKey == shortcut.homeKey } ?: return
        val home = homes.find { it.key == current.homeKey } ?: return
        val now = Instant.now()
        val rooms = home.rooms.filter { it.id in current.roomIds && it.validTemperature(current.temperature) && "TemporaryOverride" in it.allowed }
        if (prefs(context).getBoolean("demo", false)) {
            val until = current.expiry(home, now)
            val snapshot = prefs(context).getString("cache", null)?.let { snapshotFromJson(JSONObject(it)) } ?: demoSnapshot()
            val updated = snapshot.copy(homes = snapshot.homes.map { h -> if (h.key != home.key) h else h.copy(rooms = h.rooms.map { room ->
                if (room.id in rooms.map { it.id }) room.copy(target = current.temperature, mode = "TemporaryOverride", until = until) else room
            }) }, updated = now)
            prefs(context).edit().putString("cache", updated.json().toString()).commit()
            HomeActions.updateWidgets(context)
            return
        }
        if (rooms.isNotEmpty()) enqueue(context, home, rooms, current.temperature, current.expiry(home, now), source = current.id, now = now)
        if (rooms.size != current.roomIds.size) synchronized(lock) { issue(context, current.id, "Some rooms are no longer available") }
    }
    fun clear(context: Context) {
        val job = synchronized(lock) { store(context, emptyList()); prefs(context).edit().remove("commandIssues").commit(); processing }
        job?.cancel()
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val commands = pending(context)
        if (commands.isEmpty() || prefs(context).getBoolean("demo", false) || BackgroundSync.client(context).session == null) { scheduler.cancel(JOB_ID); return }
        val delay = Duration.between(Instant.now(), commands.minOf { it.next }).toMillis().coerceAtLeast(if (foreground) 60_000 else 1000)
        scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, CommandRetryService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(delay).setPersisted(true)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
    fun start(context: Context) {
        try { context.startForegroundService(Intent(context, CommandService::class.java)) }
        catch (_: Exception) { schedule(context) }
    }
    private fun current(context: Context, command: RoomCommand) = pending(context).any { it.id == command.id }
    private fun replace(context: Context, command: RoomCommand, updated: RoomCommand?) = synchronized(lock) {
        val commands = pending(context)
        if (commands.any { it.id == command.id }) store(context, commands.mapNotNull { if (it.id == command.id) updated else it })
    }
    private fun retry(context: Context, command: RoomCommand, now: Instant, rateLimited: Boolean = false) {
        replace(context, command, command.copy(attempts = command.attempts + 1, next = now.plusSeconds(commandRetrySeconds(command.attempts, rateLimited))))
    }
    /** Injected transport/clock make retry, crash recovery and supersession testable offline. */
    suspend fun runDue(context: Context, client: TccClient = BackgroundSync.client(context), clock: () -> Instant = Instant::now) = processor.withLock {
        val work = currentCoroutineContext().job
        synchronized(lock) { processing = work }
        BackgroundSync.beginOperation()
        try { withContext(Dispatchers.IO) {
            if (prefs(context).getBoolean("demo", false) || client.session == null) return@withContext
            val homes = runCatching { snapshotFromJson(JSONObject(prefs(context).getString("cache", "")!!)).homes }.getOrDefault(emptyList())
            val due = pending(context).filter { it.next <= clock() }
            for ((key, commands) in due.groupBy { it.home }) {
                ensureActive()
                val home = homes.find { it.key == key }
                val active = commands.filter { command ->
                    if (!current(context, command)) false
                    else if (command.deadline <= clock() || home?.rooms?.none { it.id == command.room } != false) {
                        synchronized(lock) { if (current(context, command)) issue(context, command.source, "Could not finish this change") }
                        replace(context, command, null); false
                    } else true
                }
                if (active.isEmpty() || home == null) continue
                val status = try { client.status(home) } catch (e: Exception) {
                    ensureActive()
                    active.forEach { command ->
                        if (e is CloudException && e.status in listOf(400, 401, 403, 404)) {
                            synchronized(lock) { if (current(context, command)) issue(context, command.source, if (e.status == 401) "Sign in to apply changes" else "Could not apply this change") }
                            replace(context, command, null)
                        } else retry(context, command, clock(), e is CloudException && e.status == 429)
                    }
                    continue
                }
                ensureActive()
                val observed = applyStatus(home, status)
                BackgroundSync.cacheStatus(context, home.key, status, clock())
                for (command in active) {
                    ensureActive()
                    if (!current(context, command)) continue
                    val actual = observed.rooms.first { it.id == command.room }
                    // An initial resume must be sent even if a preceding override is still propagating.
                    if (actual.available && command.matches(actual) && (!command.resume || command.lastWrite != null)) {
                        replace(context, command, null); continue
                    }
                    if (command.deadline <= clock()) { replace(context, command, null); continue }
                    val lastWrite = command.lastWrite
                    // Status may still show the override while the controller processes the resume.
                    if (actual.available && command.resume && lastWrite != null && clock() < lastWrite.plusSeconds(60)) {
                        replace(context, command, command.copy(next = minOf(command.deadline, clock().plusSeconds(5))))
                        continue
                    }
                    // Let accepted/ambiguous writes settle before considering an identical retry.
                    if (!actual.available || command.lastWrite?.let { Duration.between(it, clock()).seconds < 20 } == true) {
                        retry(context, command, clock()); continue
                    }
                    val writing = command.copy(lastWrite = clock(), next = clock().plusSeconds(3), attempts = command.attempts + 1)
                    replace(context, command, writing) // Persist before PUT, including the original absolute expiry.
                    if (!current(context, command)) continue
                    try { client.override(actual, command.temperature, command.until, command.resume) }
                    catch (e: Exception) {
                        ensureActive()
                        if (e is CloudException && e.status in listOf(400, 401, 403, 404)) {
                            synchronized(lock) { if (current(context, command)) issue(context, command.source, if (e.status == 401) "Sign in to apply changes" else "Could not apply this change") }
                            replace(context, command, null)
                        } else retry(context, writing, clock(), e is CloudException && e.status == 429)
                    }
                }
            }
        } } finally {
            synchronized(lock) { if (processing === work) processing = null }
            BackgroundSync.endOperation()
            HomeActions.updateWidgets(context)
        }
    }
}

/** Started only by direct interaction. The notification is silent and removed on exit. */
class CommandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var running: Job? = null
    private var latestStart = 0
    private var feedbackUntil = 0L
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); Commands.foreground = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("changes", "Applying changes", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        val open = PendingIntent.getActivity(this, 1402, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "changes").setSmallIcon(R.drawable.ic_radiator).setContentTitle("Applying temperature changes")
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setContentIntent(open).setCategory(Notification.CATEGORY_SERVICE).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1402, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(1402, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        HomeTarget.parse(intent?.data)?.takeIf { it.kind == "shortcut" }?.let { target ->
            runCatching { HomeActions.catalog(this).second.find { it.homeKey == target.home && it.id == target.id }?.let { shortcut ->
                feedbackUntil = HomeActions.acknowledge(applicationContext, shortcut.id)
                Commands.enqueueShortcut(this, shortcut)
                if (intent?.getBooleanExtra("launcherTap", false) == true) android.widget.Toast.makeText(this, "${shortcut.name} started", android.widget.Toast.LENGTH_SHORT).show()
            } }
        }
        wake.trySend(Unit)
        if (running?.isActive != true) running = scope.launch {
            try { withTimeout(75_000) {
                val end = SystemClock.elapsedRealtime() + 65_000
                while (BackgroundSync.client(applicationContext).session != null && Commands.pending(this@CommandService).isNotEmpty() && SystemClock.elapsedRealtime() < end) {
                    Commands.runDue(this@CommandService)
                    val next = Commands.pending(this@CommandService).minOfOrNull { it.next } ?: break
                    val wait = Duration.between(Instant.now(), next).toMillis().coerceAtLeast(500)
                    if (SystemClock.elapsedRealtime() + wait > end) break
                    withTimeoutOrNull(wait) { wake.receive() }
                }
                while (SystemClock.elapsedRealtime() < feedbackUntil) delay(feedbackUntil - SystemClock.elapsedRealtime())
            } } catch (_: CancellationException) { /* Queue remains durable. */ }
            catch (_: Exception) { /* Defer to JobScheduler without communication pop-ups. */ }
            finally { Commands.foreground = false; Commands.schedule(this@CommandService); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(latestStart) }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { running?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { Commands.foreground = false; scope.cancel(); super.onDestroy() }
}

class CommandRetryService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null
    private var generation = 0
    override fun onStartJob(params: JobParameters): Boolean {
        val id = ++generation
        running = scope.launch {
            try { withTimeout(90_000) { Commands.runDue(applicationContext) } }
            catch (_: Exception) { /* Retry at the system's next allowed opportunity. */ }
            finally { if (generation == id) jobFinished(params, shouldRetry()) }
        }
        return true
    }
    private fun shouldRetry() = BackgroundSync.client(applicationContext).session != null && Commands.pending(applicationContext).isNotEmpty()
    override fun onStopJob(params: JobParameters): Boolean { generation++; running?.cancel(); return shouldRetry() }
    override fun onDestroy() { generation++; scope.cancel(); super.onDestroy() }
}

/** Android launcher shortcuts require an Activity target; this one never draws UI. */
class ShortcutActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = HomeTarget.parse(intent.data)?.takeIf { it.kind == "shortcut" }
        if (target != null) {
            try { startForegroundService(Intent(this, CommandService::class.java).setData(target.uri()).putExtra("launcherTap", true)) }
            catch (_: Exception) {
                runCatching { HomeActions.catalog(this).second.find { it.id == target.id && it.homeKey == target.home }?.let { Commands.enqueueShortcut(this, it) }; Commands.schedule(this) }
            }
        }
        finish()
    }
}
