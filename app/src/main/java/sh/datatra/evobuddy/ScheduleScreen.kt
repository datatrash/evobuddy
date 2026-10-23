package sh.datatra.evobuddy

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ScheduleScreen(model: AppState, state: UiState, preferredRoom: String?, selectRoom: (String) -> Unit, back: () -> Unit) {
    val home = state.home!!
    val room = home.rooms.firstOrNull { it.id == preferredRoom } ?: home.rooms.firstOrNull()
    var day by rememberSaveable { mutableIntStateOf(0) }
    var requestedRoom by remember { mutableStateOf<String?>(null) }
    var copyDay by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val source = room?.schedule
    var draftText by rememberSaveable(home.key, room?.id, source?.json()?.toString()) { mutableStateOf(source?.json()?.toString()) }
    val draft = draftText?.let { runCatching { WeekSchedule.parse(JSONObject(it)) }.getOrNull() }
    val dirty = draft != source
    fun change(schedule: WeekSchedule) { draftText = schedule.json().toString() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && exportText != null) {
            runCatching { checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(exportText!!) } }
                .onSuccess { model.notify("Schedule exported.") }.onFailure { model.notify("Could not export this schedule.", true) }
        }
        exportText = null
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && room != null) {
            runCatching {
                val text = checkNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                    val bytes = ByteArray(262145)
                    var total = 0
                    while (total < bytes.size) { val count = stream.read(bytes, total, bytes.size - total); if (count < 0) break; total += count }
                    require(total < bytes.size) { "Schedule file is too large." }; String(bytes, 0, total, Charsets.UTF_8)
                }
                val schedule = WeekSchedule.parse(JSONObject(text)); schedule.validate(room); change(schedule)
            }.onSuccess { model.notify("Imported as a draft. Review it before saving.") }.onFailure { model.notify(it.message ?: "Could not import this schedule.", true) }
        }
    }
    LaunchedEffect(home.key, room?.id) { if (room != null && room.schedule == null) model.loadSchedule(room) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to rooms") }
            Text("Schedule", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            SyncIndicator(model, state)
        }
        if (state.error && state.message != null) Box(Modifier.padding(horizontal = 24.dp)) { InlineError(state) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(home.rooms, key = { it.id }) { r -> FilterChip(selected = r.id == room?.id, onClick = { if (dirty) requestedRoom = r.id else selectRoom(r.id) }, label = { Text(r.name) }, enabled = !state.busy) } } }
        if (room == null) item { Hint("No heating zones were found.") }
        else if (source == null || draft == null) {
            item { Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Load ${room.name}’s schedule", style = MaterialTheme.typography.titleMedium); Hint("The controller’s saved plan is needed before editing. If TCC could not load it, you can retry here."); Button(onClick = { model.loadSchedule(room, true) }, enabled = !state.busy) { Text("Load schedule") } } } }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(room.name, style = MaterialTheme.typography.titleLarge); if (dirty) Pill("Unsaved draft") }
            }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { weekdays.forEachIndexed { index, name -> FilterChip(selected = day == index, onClick = { day = index }, label = { Text(name.take(3)) }) } } }
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Eyebrow(weekdays[day]); ScheduleTimeline(draft, day)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Hint("00:00"); Hint("12:00"); Hint("24:00") }
                        Hint("Temperatures stay active until the next change.")
                    }
                }
            }
            items(draft.days[day].indices.toList(), key = { "$day:$it" }) { index ->
                val point = draft.days[day][index]
                Surface(onClick = { editing = index }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(point.time, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text(temp(point.temperature), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp)); Icon(Icons.Default.Edit, "Edit ${point.time} change", Modifier.size(18.dp))
                    }
                }
            }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = { adding = true }, enabled = draft.days[day].size < room.maxPoints) { Icon(Icons.Default.Add, null); Text("Add change") }; TextButton(onClick = { copyDay = true }) { Text("Copy day") } } }
            item { Button(onClick = { preview = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (dirty) "Review & save" else "Copy plan to rooms") } }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { exportText = draft.json().toString(2); export.launch("evobuddy-${room.name.replace(Regex("[^a-zA-Z0-9]"), "-")}.json") }) { Text("Export") }
                TextButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !state.busy) { Text("Import draft") }
                TextButton(onClick = { model.backup(room)?.let { change(it); model.notify("Backup loaded as a draft. Review before restoring.") } }, enabled = !state.busy && model.backup(room) != null) { Text("Restore backup") }
            } }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dirty) TextButton(onClick = { change(source) }, enabled = !state.busy) { Text("Discard draft") }
                TextButton(onClick = { model.loadSchedule(room, true) }, enabled = !state.busy && !dirty) { Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Reload from TCC") }
            }; Hint("${room.minPoints}–${room.maxPoints} changes per day · ${room.timeStep}-minute time steps · ${home.zoneId}") }
        }
    }
    }
    if (requestedRoom != null) AlertDialog(onDismissRequest = { requestedRoom = null }, title = { Text("Discard unsaved changes?") }, text = { Text("Your draft for ${room?.name} has not been saved to Evohome.") }, confirmButton = { TextButton(onClick = { selectRoom(requestedRoom!!); requestedRoom = null }) { Text("Discard & switch") } }, dismissButton = { TextButton(onClick = { requestedRoom = null }) { Text("Keep editing") } })
    if (room != null && source != null && draft != null) {
        if (editing != null || adding) {
            val index = editing
            PointDialog(room, if (index != null) draft.days[day][index] else null, draft.days[day], { editing = null; adding = false }, { point ->
                val points = draft.days[day].toMutableList()
                if (index != null) { if (point == null) points.removeAt(index) else points[index] = point } else if (point != null) points.add(point)
                change(draft.copy(days = draft.days.mapIndexed { i, p -> if (i == day) points.sortedBy { it.minute } else p }))
                editing = null; adding = false
            })
        }
        if (copyDay) CopyDayDialog(day, { copyDay = false }) { targets ->
            change(draft.copy(days = draft.days.mapIndexed { index, points -> if (index in targets) draft.days[day].toList() else points })); copyDay = false
        }
        if (preview) SaveScheduleDialog(home, room, source, draft, state.busy, { preview = false }) { targets -> model.saveSchedules(room, source, draft, targets); preview = false }
    }
}

@Composable fun ScheduleTimeline(schedule: WeekSchedule, day: Int) {
    val cold = MaterialTheme.colorScheme.secondaryContainer
    val medium = MaterialTheme.colorScheme.primaryContainer
    val warm = MaterialTheme.colorScheme.primary
    val prior = (1..7).firstNotNullOfOrNull { back -> schedule.days[(day - back + 7) % 7].lastOrNull()?.temperature } ?: 16.0
    val points = listOf(Switchpoint(0, prior)) + schedule.days[day]
    Canvas(Modifier.fillMaxWidth().height(66.dp).semantics { contentDescription = "24 hour temperature timeline for ${weekdays[day]}" }) {
        points.forEachIndexed { index, point ->
            val end = points.getOrNull(index + 1)?.minute ?: 1440
            val x = size.width * point.minute / 1440f
            val width = size.width * (end - point.minute) / 1440f
            drawRect(if (point.temperature < 18) cold else if (point.temperature < 20) medium else warm, Offset(x, 0f), Size(width.coerceAtLeast(0f), size.height))
        }
        (1..3).forEach { drawLine(Color.White.copy(alpha = .4f), Offset(size.width * it / 4, 0f), Offset(size.width * it / 4, size.height), 2f) }
    }
}
@Composable private fun PointDialog(room: Room, initial: Switchpoint?, existing: List<Switchpoint>, dismiss: () -> Unit, save: (Switchpoint?) -> Unit) {
    val candidate = (0..1439 step room.timeStep).firstOrNull { minute -> existing.none { it.minute == minute } } ?: 0
    var minute by remember { mutableIntStateOf(initial?.minute ?: candidate) }
    var temperature by remember { mutableDoubleStateOf(initial?.temperature ?: (room.min + kotlin.math.round((20.0 - room.min) / room.step) * room.step).coerceIn(room.min, room.max)) }
    val valid = minute % room.timeStep == 0 && existing.none { it != initial && it.minute == minute } && room.validTemperature(temperature)
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (initial == null) "Add a change" else "Edit this change") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TimeChoice("Start time", LocalTime.of(minute / 60, minute % 60)) { minute = it.hour * 60 + it.minute }
            Hint(if (existing.any { it != initial && it.minute == minute }) "A change already exists at this time." else "Use ${room.timeStep}-minute steps.")
            TemperatureControl(temperature, room) { temperature = it }
            Hint("This temperature lasts until the next scheduled change.")
            if (initial != null) TextButton(onClick = { save(null) }, enabled = existing.size > room.minPoints) { Text("Delete change", color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { save(Switchpoint(minute, temperature)) }, enabled = valid) { Text("Keep change") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@Composable private fun CopyDayDialog(day: Int, dismiss: () -> Unit, save: (Set<Int>) -> Unit) {
    var targets by remember { mutableStateOf(emptySet<Int>()) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Copy ${weekdays[day]}") }, text = {
        Column { Hint("Copies into this draft. Save the plan to apply."); weekdays.forEachIndexed { index, name -> if (index != day) Row(Modifier.fillMaxWidth().clickable { targets = if (index in targets) targets - index else targets + index }, verticalAlignment = Alignment.CenterVertically) { Checkbox(index in targets, { targets = if (it) targets + index else targets - index }); Text(name) } } }
    }, confirmButton = { TextButton(onClick = { save(targets) }, enabled = targets.isNotEmpty()) { Text("Copy to ${targets.size} days") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@Composable private fun SaveScheduleDialog(home: Home, sourceRoom: Room, original: WeekSchedule, draft: WeekSchedule, busy: Boolean, dismiss: () -> Unit, save: (Set<String>) -> Unit) {
    var targets by remember { mutableStateOf(if (original != draft) setOf(sourceRoom.id) else emptySet()) }
    val error = targets.mapNotNull { id -> home.rooms.firstOrNull { it.id == id }?.let { room -> runCatching { draft.validate(room) }.exceptionOrNull()?.let { "${room.name}: ${it.message}" } } }.firstOrNull()
    val changed = weekdays.indices.filter { original.days[it] != draft.days[it] }.joinToString { weekdays[it].take(3) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Review your plan") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text(if (changed.isEmpty()) "Copy ${sourceRoom.name}’s weekly plan to the selected rooms." else "Changed days: $changed. Apply the full weekly plan to:") }
            items(home.rooms, key = { it.id }) { room -> Row(Modifier.fillMaxWidth().clickable { targets = if (room.id in targets) targets - room.id else targets + room.id }, verticalAlignment = Alignment.CenterVertically) { Checkbox(room.id in targets, { targets = if (it) targets + room.id else targets - room.id }); Text(room.name) } }
            item { Hint("Replaces the full week. Each room’s current plan is backed up first. Failures are listed per room.") }
            if (error != null) item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = { save(targets) }, enabled = !busy && targets.isNotEmpty() && error == null) { Text("Save to ${targets.size} room(s)") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Keep editing") } })
}
