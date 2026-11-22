package sh.datatra.evobuddy

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class UiState(
    val snapshot: Snapshot? = null, val homeKey: String? = null, val demo: Boolean = false,
    val connected: Boolean = false, val busy: Boolean = false, val refreshing: Boolean = false, val live: Boolean = false, val message: String? = null,
    val error: Boolean = false, val favourites: Set<String> = emptySet(), val shortcuts: List<Shortcut> = emptyList(),
    val theme: String = "System", val scheduleErrors: Map<String, String> = emptyMap(), val scheduleSync: Map<String, Instant> = emptyMap(),
    val roomOrder: Map<String, List<String>> = emptyMap(), val shortcutRuns: Map<String, List<RoomCommandResult>> = emptyMap(),
    val pendingChanges: List<RoomCommand> = emptyList(), val commandIssues: Map<String, String> = emptyMap(), val reconnecting: Boolean = false
) { val home: Home? get() = snapshot?.homes?.firstOrNull { it.key == homeKey } ?: snapshot?.homes?.firstOrNull() }

class AppState @JvmOverloads constructor(application: Application, private val loginClient: TccClient = TccClient()) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("evobuddy", Context.MODE_PRIVATE)
    private val vault = TokenVault(application)
    private val client = BackgroundSync.client(application)
    private fun hasSavedAccount() = prefs.contains("tccAccountId") || prefs.contains("cache") || prefs.contains("shortcuts")
    private val _state = MutableStateFlow(UiState(
        snapshot = runCatching { prefs.getString("cache", null)?.let { snapshotFromJson(JSONObject(it)) } }.getOrNull(),
        homeKey = prefs.getString("home", null), demo = prefs.getBoolean("demo", false), connected = client.session != null,
        pendingChanges = Commands.pending(application), commandIssues = Commands.issues(application),
        reconnecting = client.session == null && !prefs.getBoolean("demo", false) && hasSavedAccount(),
        favourites = prefs.getStringSet("favourites", emptySet())!!.toSet(), theme = prefs.getString("theme", "System")!!,
        shortcuts = runCatching { JSONArray(prefs.getString("shortcuts", "[]")).objects().map(Shortcut::parse) }.getOrDefault(emptyList()),
        roomOrder = runCatching { JSONObject(prefs.getString("roomOrder", "{}")!!).let { json -> json.keys().asSequence().associateWith { key -> json.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } } } } }.getOrDefault(emptyMap())
    ))
    val state = _state.asStateFlow()
    private var operationVersion = 0L
    private var refreshJob: Job? = null
    private var scheduleJob: Job? = null
    private var nextRefresh = Instant.EPOCH
    private val handler = Handler(Looper.getMainLooper())
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "cache" || key == "pendingChanges" || key == "commandIssues") handler.post {
            val pending = if (key == "pendingChanges") Commands.pending(getApplication()) else _state.value.pendingChanges
            if (key == "pendingChanges" && _state.value.pendingChanges.map { it.id } != pending.map { it.id }) {
                operationVersion++; refreshJob?.cancel(); scheduleJob?.cancel()
                _state.update { it.copy(refreshing = false) }
            }
            if (_state.value.pendingChanges.isNotEmpty() && pending.isEmpty()) nextRefresh = Instant.EPOCH
            _state.update { it.copy(pendingChanges = pending, commandIssues = Commands.issues(getApplication())) }
            reloadBackgroundCache()
        }
    }
    init {
        if (_state.value.demo) _state.update { it.copy(snapshot = it.snapshot ?: demoSnapshot(), connected = false) }
        HomeActions.sync(application)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        viewModelScope.launch {
            client.sessionRejected.collect { rejected ->
                if (rejected && hasSavedAccount() && !_state.value.demo) {
                    BackgroundSync.cancel(application)
                    Commands.clear(application)
                    _state.update { it.copy(connected = false, reconnecting = true, refreshing = false, live = false) }
                    HomeActions.updateWidgets(application)
                }
            }
        }
    }
    override fun onCleared() { prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener); handler.removeCallbacksAndMessages(null); super.onCleared() }
    private fun publish(snapshot: Snapshot) {
        _state.update { it.copy(snapshot = snapshot) }
        if (!_state.value.demo) BackgroundSync.storeCache(getApplication(), snapshot)
        else { prefs.edit().putString("cache", snapshot.json().toString()).apply(); HomeActions.updateRoomWidgets(getApplication()) }
    }
    private fun updateHome(home: Home, fresh: Boolean = false) {
        val snapshot = _state.value.snapshot ?: return
        publish(snapshot.copy(homes = snapshot.homes.map { if (it.key == home.key) home else it }, updated = if (fresh) Instant.now() else snapshot.updated))
    }
    // Routine sync and success are represented only by the small activity indicator.
    fun notify(message: String, error: Boolean = false) { _state.update { it.copy(message = if (error) message else null, error = error) } }
    fun dismiss() { _state.update { it.copy(message = null) } }
    private fun launch(message: String, action: suspend () -> Unit) {
        if (_state.value.busy) return
        BackgroundSync.beginOperation()
        operationVersion++
        refreshJob?.cancel(); scheduleJob?.cancel()
        _state.update { it.copy(busy = true, refreshing = false, message = null, error = false) }
        viewModelScope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val text = when (e) {
                    is CloudException, is IllegalArgumentException, is IllegalStateException -> e.message ?: "Could not complete the request."
                    else -> "Could not complete this change."
                }
                notify(text, true)
            } finally { BackgroundSync.endOperation(); _state.update { it.copy(busy = false) } }
        }
    }
    fun enterDemo() {
        if (_state.value.busy) return
        // Clear the real account before entering demo; sample data can never be sent to TCC.
        logout()
        prefs.edit().putBoolean("demo", true).apply()
        _state.update { it.copy(demo = true, homeKey = "demo-demo", message = null) }
        publish(demoSnapshot())
        HomeActions.sync(getApplication())
    }
    fun logout() {
        if (_state.value.busy) return
        BackgroundSync.cancel(getApplication())
        Commands.clear(getApplication())
        operationVersion++; refreshJob?.cancel(); scheduleJob?.cancel()
        client.clearSession(); vault.clear()
        val theme = _state.value.theme
        prefs.edit().clear().putString("theme", theme).apply()
        _state.value = UiState(theme = theme)
        HomeActions.disconnect(getApplication())
    }
    fun reconnect() {
        if (_state.value.busy || _state.value.demo) return
        operationVersion++
        refreshJob?.cancel(); scheduleJob?.cancel()
        _state.update { it.copy(reconnecting = true, refreshing = false, message = null, error = false) }
    }
    fun cancelReconnect() {
        if (!_state.value.busy && client.session != null) {
            _state.update { it.copy(reconnecting = false, message = null, error = false) }
        }
    }
    fun login(email: String, password: String) = launch("Connecting to European TCC…") {
        require(email.isNotBlank() && password.isNotEmpty()) { "Enter your TCC email and password." }
        val savedAccount = prefs.getString("tccAccountId", null)
        val previous = _state.value.snapshot
        val recovering = hasSavedAccount()
        val (account, snapshot, session) = withContext(Dispatchers.IO) {
            try {
                loginClient.login(email, password)
                val account = loginClient.accountId()
                require(savedAccount == null || savedAccount == account) { "Sign in with your original TCC account to recover your saved settings." }
                val homes = loginClient.discover(account)
                require(homes.isNotEmpty()) { "No Evohome systems were found on this European TCC account." }
                if (recovering && savedAccount == null) {
                    val previousKeys = previous?.homes.orEmpty().map { it.key }.toSet()
                    require(previousKeys.isNotEmpty() && homes.map { it.key }.containsAll(previousKeys)) {
                        "This account does not have access to the saved Evohome systems. Sign in with your original account."
                    }
                }
                val statuses = homes.distinctBy { it.locationId }.associate { it.locationId to loginClient.status(it) }
                val restored = homes.map { applyStatus(it, statuses.getValue(it.locationId)) }
                Triple(account, Snapshot(restored, Instant.now()), checkNotNull(loginClient.session))
            } finally {
                loginClient.clearSession()
            }
        }
        withContext(Dispatchers.IO) {
            if (Commands.pending(getApplication()).isNotEmpty() || Commands.issues(getApplication()).isNotEmpty()) Commands.clear(getApplication())
            check(prefs.edit().putString("tccAccountId", account).commit()) { "Could not save the TCC account." }
            client.replaceSession(session)
        }
        publish(snapshot)
        _state.update { it.copy(connected = true, reconnecting = false, live = true, message = null, error = false) }
        nextRefresh = Instant.now().plusSeconds(180)
        warmSchedules()
        BackgroundSync.ensureScheduled(getApplication())
        HomeActions.sync(getApplication())
    }
    fun selectHome(key: String) { if (!_state.value.busy) { scheduleJob?.cancel(); scheduleJob = null; _state.update { it.copy(homeKey = key) }; prefs.edit().putString("home", key).apply(); warmSchedules() } }
    fun refresh(automatic: Boolean = false) {
        if (_state.value.busy || _state.value.refreshing || _state.value.reconnecting) return
        if (automatic && Commands.pending(getApplication()).isNotEmpty()) return
        nextRefresh = Instant.now().plusSeconds(180)
        val version = operationVersion
        _state.update { it.copy(refreshing = true, live = false) }
        refreshJob = viewModelScope.launch {
            try {
                if (_state.value.demo) refreshInternal()
                else {
                    val knownAccount = prefs.getString("tccAccountId", null)
                    val account = knownAccount ?: withContext(Dispatchers.IO) { client.accountId() }
                    val homes = _state.value.snapshot?.homes ?: withContext(Dispatchers.IO) { client.discover(account) }
                    require(homes.isNotEmpty()) { "No Evohome systems were found." }
                    val statuses = withContext(Dispatchers.IO) { homes.distinctBy { it.locationId }.associate { it.locationId to client.status(it) } }
                    // Discard a read started before a newer heating command.
                    if (version == operationVersion) {
                        if (knownAccount == null) prefs.edit().putString("tccAccountId", account).apply()
                        val latest = _state.value.snapshot?.homes ?: homes
                        publish(Snapshot(latest.map { applyStatus(it, statuses.getValue(it.locationId)) }, Instant.now()))
                        _state.update { it.copy(live = true, error = false, message = null) }
                    }
                }
                if (version == operationVersion) warmSchedules()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                nextRefresh = Instant.now().plusSeconds(if (e is CloudException && e.status == 429) 60 else 15)
                if (version == operationVersion && e is CloudException && e.status != 429) notify(e.message ?: "Could not refresh temperatures.", true)
            }
            finally { if (version == operationVersion) _state.update { it.copy(refreshing = false) } }
        }
    }
    fun refreshAutomatically() { if (Instant.now() >= nextRefresh) refresh(automatic = true) }
    fun acknowledgeSyncIssue() { Commands.clearIssues(getApplication()); _state.update { it.copy(commandIssues = emptyMap(), error = false, message = null) } }
    fun reloadBackgroundCache() {
        if (_state.value.busy) return
        val cached = runCatching { prefs.getString("cache", null)?.let { snapshotFromJson(JSONObject(it)) } }.getOrNull() ?: return
        if (_state.value.snapshot?.updated?.isBefore(cached.updated) != false) _state.update { it.copy(snapshot = cached, live = false) }
    }
    private fun warmSchedules() {
        if (scheduleJob?.isActive == true || _state.value.demo) return
        scheduleJob = viewModelScope.launch { loadMissingSchedules() }
    }
    private suspend fun loadMissingSchedules() {
        if (_state.value.demo) return
        val home = _state.value.home ?: return
        for (room in home.rooms.filter { it.schedule == null }) {
            val version = operationVersion
            try {
                val schedule = withContext(Dispatchers.IO) { client.schedule(room) }
                val current = _state.value.home ?: return
                if (version != operationVersion || current.key != home.key) return
                updateHome(current.copy(rooms = current.rooms.map { if (it.id == room.id) it.copy(schedule = schedule) else it }))
                _state.update { it.copy(scheduleSync = it.scheduleSync + ("${home.key}:${room.id}" to Instant.now())) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(scheduleErrors = it.scheduleErrors + (room.id to "Schedule unavailable")) } }
        }
    }
    private suspend fun refreshInternal() {
        if (_state.value.demo) {
            _state.value.snapshot?.homes?.forEach { home -> updateHome(home.copy(rooms = home.rooms.map { room ->
                if (room.until != null && room.until <= Instant.now()) room.copy(mode = "FollowSchedule", until = null, target = room.schedule?.target(Instant.now(), home.zone)) else room
            }), true) }; return
        }
        if (_state.value.snapshot == null) {
            val homes = withContext(Dispatchers.IO) { client.discover() }
            require(homes.isNotEmpty()) { "No Evohome systems were found." }
            publish(Snapshot(homes, Instant.EPOCH))
        }
        val snapshot = _state.value.snapshot ?: return
        val statuses = withContext(Dispatchers.IO) { snapshot.homes.distinctBy { it.locationId }.associate { it.locationId to client.status(it) } }
        publish(Snapshot(snapshot.homes.map { applyStatus(it, statuses.getValue(it.locationId)) }, Instant.now()))
        _state.update { it.copy(live = true) }
    }
    fun toggleFavourite(room: Room) {
        val key = "${_state.value.home?.key}:${room.id}"
        val favourites = _state.value.favourites.toMutableSet().apply { if (!add(key)) remove(key) }
        _state.update { it.copy(favourites = favourites) }; prefs.edit().putStringSet("favourites", favourites).apply()
    }
    fun setTheme(theme: String) { _state.update { it.copy(theme = theme) }; prefs.edit().putString("theme", theme).apply() }
    fun moveRoom(from: String, to: String) {
        val state = _state.value
        val home = state.home ?: return
        val ids = orderedRooms(home, state.roomOrder[home.key].orEmpty(), state.favourites).map { it.id }
        _state.update { it.copy(roomOrder = it.roomOrder + (home.key to moveRoomOrder(ids, from, to))) }
    }
    fun persistRoomOrder() {
        val json = JSONObject()
        _state.value.roomOrder.forEach { (key, ids) -> json.put(key, JSONArray(ids)) }
        prefs.edit().putString("roomOrder", json.toString()).apply()
    }
    fun loadSchedule(room: Room, force: Boolean = false) = launch("Loading ${room.name} schedule…") {
        val home = _state.value.home ?: return@launch
        val latest = home.rooms.first { it.id == room.id }
        if (_state.value.demo || latest.schedule != null && !force) { dismiss(); return@launch }
        val schedule = withContext(Dispatchers.IO) { client.schedule(latest) }
        updateHome(home.copy(rooms = home.rooms.map { if (it.id == room.id) it.copy(schedule = schedule) else it }))
        _state.update { it.copy(scheduleSync = it.scheduleSync + ("${home.key}:${room.id}" to Instant.now())) }
        _state.update { it.copy(scheduleErrors = it.scheduleErrors - room.id) }; dismiss()
    }
    fun override(room: Room, temperature: Double?, until: Instant?, resume: Boolean = false) = launch(if (resume) "Resuming ${room.name} schedule…" else "Sending change to ${room.name}…") {
        val home = _state.value.home ?: return@launch
        if (!_state.value.demo) {
            Commands.enqueue(getApplication(), home, listOf(room), temperature, until, resume, "room:${home.key}:${room.id}")
            Commands.start(getApplication())
            return@launch
        }
        if (_state.value.demo) {
            delay(250)
            updateHome(home.copy(rooms = home.rooms.map { if (it.id == room.id) it.copy(target = if (resume) it.schedule?.target(Instant.now(), home.zone) else temperature, mode = if (resume) "FollowSchedule" else if (until == null) "PermanentOverride" else "TemporaryOverride", until = until) else it }), true)
            notify("Applied in demo. No heating devices were changed."); return@launch
        }
    }
    fun setMode(option: ModeOption, until: Instant?) = launch("Changing home mode…") {
        val home = _state.value.home ?: return@launch
        if (_state.value.demo) { updateHome(home.copy(mode = if (option.id == "AutoWithReset") "Auto" else option.id, modeUntil = until, rooms = home.rooms.map { if (option.id == "AutoWithReset") it.copy(mode = "FollowSchedule", until = null, target = it.schedule?.target(Instant.now(), home.zone)) else it }), true); notify("Home mode changed in demo."); return@launch }
        val response = withContext(Dispatchers.IO) { client.setMode(home, option, until) }
        val confirmed = withContext(Dispatchers.IO) { client.waitTask(response) }
        refreshInternal()
        notify(if (confirmed) "Controller confirmed the home mode." else "Mode sent to TCC; controller confirmation pending.")
    }
    fun saveSchedules(source: Room, original: WeekSchedule, edited: WeekSchedule, targetIds: Set<String>) = launch("Saving schedules…") {
        val home = _state.value.home ?: return@launch
        require(targetIds.isNotEmpty()) { "Choose at least one room." }
        val results = mutableListOf<String>()
        for (room in home.rooms.filter { it.id in targetIds }) {
            try {
                edited.validate(room)
                val previous = if (_state.value.demo) room.schedule!! else withContext(Dispatchers.IO) { client.schedule(room) }
                if (room.id == source.id) require(previous == original) { "Schedule changed elsewhere. Reload it before saving." }
                // Persist the controller's actual pre-write schedule before every write, including copies/restores.
                check(prefs.edit().putString("backup:${home.key}:${room.id}", previous.json().toString()).commit()) { "Could not save a backup; no schedule was sent." }
                val confirmed = if (_state.value.demo) true else withContext(Dispatchers.IO) { client.waitTask(client.saveSchedule(room, edited)) }
                val saved = if (_state.value.demo) edited else withContext(Dispatchers.IO) { client.schedule(room) }
                require(saved == edited) { "The saved schedule differs. Check the latest plan before changing it again." }
                val current = _state.value.home!!
                updateHome(current.copy(rooms = current.rooms.map { if (it.id == room.id) it.copy(schedule = saved) else it }))
                _state.update { it.copy(scheduleSync = it.scheduleSync + ("${home.key}:${room.id}" to Instant.now())) }
                results += "${room.name}: ${if (confirmed) "saved" else "saved in TCC; controller pending"}"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                results += "${room.name}: FAILED — ${if (e is CloudException || e is IllegalArgumentException || e is IllegalStateException) e.message else "connection interrupted; check before retrying"}"
            }
        }
        notify(results.joinToString("\n"), results.any { it.contains("FAILED") })
    }
    fun backup(room: Room): WeekSchedule? = prefs.getString("backup:${_state.value.home?.key}:${room.id}", null)?.let { runCatching { WeekSchedule.parse(JSONObject(it)) }.getOrNull() }
    fun saveShortcut(name: String, roomIds: List<String>, temperature: Double, minutes: Int, endTime: String? = null, shortcutId: String? = null) {
        val home = _state.value.home ?: return
        val existing = shortcutId?.let { id -> requireNotNull(_state.value.shortcuts.find { it.id == id && it.homeKey == home.key }) { "Shortcut no longer available in this home." } }
        val rooms = roomIds.distinct().map { id -> requireNotNull(home.rooms.find { it.id == id }) { "Room no longer available." } }
        require(name.isNotBlank() && minutes in 1..1440 && rooms.isNotEmpty()) { "Choose a name, rooms and duration." }
        require(rooms.all { it.validTemperature(temperature) && "TemporaryOverride" in it.allowed }) { "Choose a temperature supported by every selected room." }
        val end = endTime?.let { java.time.LocalTime.parse(it).withSecond(0).withNano(0).toString() }
        val shortcut = Shortcut(existing?.id ?: UUID.randomUUID().toString(), name.trim(), home.key, rooms.map { it.id }, temperature, minutes, end)
        storeShortcuts(if (existing == null) _state.value.shortcuts + shortcut else _state.value.shortcuts.map { if (it.id == existing.id) shortcut else it })
        if (existing != null) _state.update { it.copy(shortcutRuns = it.shortcutRuns - existing.id) }
        notify("Shortcut saved.")
    }
    fun deleteShortcut(id: String) = storeShortcuts(_state.value.shortcuts.filterNot { it.id == id })
    private fun storeShortcuts(shortcuts: List<Shortcut>) {
        _state.update { it.copy(shortcuts = shortcuts) }
        prefs.edit().putString("shortcuts", JSONArray(shortcuts.map { it.json() }).toString()).apply()
        HomeActions.sync(getApplication())
    }
    fun runShortcut(shortcut: Shortcut) = launch("Running shortcut…") {
        val home = _state.value.home ?: return@launch
        require(home.key == shortcut.homeKey) { "Switch to this shortcut’s home first." }
        if (!_state.value.demo) {
            Commands.enqueueShortcut(getApplication(), shortcut)
            _state.update { it.copy(shortcutRuns = it.shortcutRuns - shortcut.id) }
            Commands.start(getApplication())
            return@launch
        }
        val until = shortcut.expiry(home, Instant.now())
        val results = shortcut.roomIds.associateWith { id -> RoomCommandResult(id, home.rooms.find { it.id == id }?.name ?: "Removed room", "Sending") }.toMutableMap()
        fun showResults() { _state.update { it.copy(shortcutRuns = it.shortcutRuns + (shortcut.id to results.values.toList())) } }
        showResults()
        // Send every room once with the same expiry, before waiting for acknowledgement.
        for (id in shortcut.roomIds) {
            val room = home.rooms.find { it.id == id }
            try {
                requireNotNull(room) { "Room removed" }
                require(room.available) { "Room unavailable" }
                require(room.validTemperature(shortcut.temperature) && "TemporaryOverride" in room.allowed) { "Override unsupported" }
                if (_state.value.demo) {
                    val current = _state.value.home!!
                    updateHome(current.copy(rooms = current.rooms.map { if (it.id == id) it.copy(target = shortcut.temperature, mode = "TemporaryOverride", until = until) else it }), true)
                    results[id] = RoomCommandResult(id, room.name, "Applied in demo")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                results[id] = RoomCommandResult(id, room?.name ?: "Removed room", "Failed: ${e.message ?: "Room unavailable"}")
            }
            showResults()
        }
    }
}
