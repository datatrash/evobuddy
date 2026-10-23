package sh.datatra.evobuddy

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.round

class MainActivity : ComponentActivity() {
    private var homeTarget by mutableStateOf<HomeTarget?>(null)
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); setIntent(intent); homeTarget = HomeTarget.parse(intent.data)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("homeTargetConsumed", homeTarget == null)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() { super.onStart(); BackgroundSync.enterForeground() }
    override fun onStop() { BackgroundSync.leaveForeground(applicationContext); super.onStop() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean("homeTargetConsumed") != true) homeTarget = HomeTarget.parse(intent.data)
        enableEdgeToEdge()
        setContent {
            val model: AppState = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            val dark = state.theme == "Dark" || state.theme == "System" && isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            EvoTheme(dark) { EvoApp(model, state, homeTarget) { homeTarget = null; intent.data = null } }
        }
    }
}
private val LightColors = lightColorScheme(primary = Color(0xFF985014), onPrimary = Color.White, primaryContainer = Color(0xFFFFE2BC), onPrimaryContainer = Color(0xFF66330B), secondary = Color(0xFF526A5C), secondaryContainer = Color(0xFFE1EADF), onSecondaryContainer = Color(0xFF294334), background = Color(0xFFF8F6F0), onBackground = Color(0xFF252E2A), surface = Color(0xFFFFFDF8), onSurface = Color(0xFF252E2A), surfaceVariant = Color(0xFFEDECE4), onSurfaceVariant = Color(0xFF62665F), outlineVariant = Color(0xFFDEDFD5))
private val DarkColors = darkColorScheme(primary = Color(0xFFF4B468), onPrimary = Color(0xFF402100), primaryContainer = Color(0xFF563B22), onPrimaryContainer = Color(0xFFFFDDB8), secondary = Color(0xFFA8C6AE), secondaryContainer = Color(0xFF30483A), onSecondaryContainer = Color(0xFFD0E8D4), background = Color(0xFF171D19), onBackground = Color(0xFFE7EAE2), surface = Color(0xFF222A24), onSurface = Color(0xFFE7EAE2), surfaceVariant = Color(0xFF303930), onSurfaceVariant = Color(0xFFB2BCAF), outlineVariant = Color(0xFF414A40))
@Composable fun EvoTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = Typography(
        headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontFamily = FontFamily.Serif, fontSize = 36.sp, lineHeight = 40.sp),
        headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Serif, fontSize = 30.sp),
        titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium)
    ), shapes = Shapes(medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)), content = content)
}
@Composable fun RadiatorMark(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.size(32.dp)) {
        val s = size.width / 32
        listOf(4f, 12f, 20f).forEachIndexed { i, x -> drawRoundRect(color, Offset(x * s, (if (i == 1) 5 else 9) * s), Size(5 * s, (if (i == 1) 23 else 19) * s), CornerRadius(2 * s)) }
        drawLine(color, Offset(7 * s, 23 * s), Offset(25 * s, 23 * s), 3 * s)
    }
}
@Composable fun Eyebrow(text: String) { Text(text.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.7.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable fun Hint(text: String, modifier: Modifier = Modifier) { Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable fun Pill(text: String, onClick: (() -> Unit)? = null) {
    Surface(modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick), color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
@Composable fun EvoApp(model: AppState, state: UiState, target: HomeTarget? = null, consumed: () -> Unit = {}) { key("navigation-v2") { EvoAppContent(model, state, target, consumed) } }
@Composable private fun EvoAppContent(model: AppState, state: UiState, target: HomeTarget?, consumed: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var scheduleRoom by rememberSaveable { mutableStateOf<String?>(null) }
    val screenState = rememberSaveableStateHolder()
    var selectedRoom by remember { mutableStateOf<String?>(null) }
    var showMode by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(target) {
        if (target != null) {
            val home = model.state.value.snapshot?.homes?.find { it.key == target.home }
            when {
                home == null -> model.notify("This home is unavailable. Open EvoBuddy and check your account.", true)
                state.busy -> model.notify("A change is in progress. Try the shortcut again when it finishes.", true)
                else -> {
                    model.selectHome(home.key)
                    scheduleRoom = null; showMode = false; selectedRoom = null
                    if (target.kind == "room") {
                        tab = 0
                        if (home.rooms.any { it.id == target.id }) selectedRoom = target.id
                        else model.notify("This room is no longer available.", true)
                    } else {
                        tab = 1
                        val shortcut = model.state.value.shortcuts.find { it.id == target.id && it.homeKey == home.key }
                        if (shortcut == null) model.notify("This shortcut is no longer available.", true)
                        else model.runShortcut(shortcut)
                    }
                }
            }
            consumed()
        }
    }
    BackHandler(enabled = scheduleRoom != null) { scheduleRoom = null }
    LaunchedEffect(state.connected, state.demo) {
        if (state.connected || state.demo) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.reloadBackgroundCache()
            model.refresh()
            while (true) { delay(15_000); model.reloadBackgroundCache(); model.refreshAutomatically() }
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        if (state.home != null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            listOf("Home" to Icons.Default.Home, "Shortcuts" to Icons.Default.PlayArrow, "Settings" to Icons.Default.Settings).forEachIndexed { index, item ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index; scheduleRoom = null }, icon = { Icon(item.second, null) }, label = { Text(item.first) }, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).widthIn(max = 760.dp)) {
            val home = state.home
            if (home == null) Welcome(model, state)
            else {
                screenState.SaveableStateProvider("${home.key}:${if (scheduleRoom != null) "schedule" else tab}") {
                    if (scheduleRoom != null) ScheduleScreen(model, state, scheduleRoom, { scheduleRoom = it }, { scheduleRoom = null })
                    else when (tab) {
                        0 -> HomeScreen(model, state, { selectedRoom = it.id }, { showMode = true })
                        1 -> ShortcutsScreen(model, state)
                        else -> SettingsScreen(model, state)
                    }
                }
                home.rooms.firstOrNull { it.id == selectedRoom }?.let { room ->
                    RoomSheet(model, state, room, { selectedRoom = null }, { selectedRoom = null; scheduleRoom = room.id })
                }
                if (showMode) ModeDialog(model, state) { showMode = false }
            }
        }
    }
}
fun hasFreshReadings(state: UiState, now: Instant): Boolean = state.live && state.snapshot?.updated?.let { java.time.Duration.between(it, now).seconds in 0..180 } == true

@Composable fun SyncIndicator(model: AppState, state: UiState) {
    val attention = state.error || state.commandIssues.isNotEmpty()
    IconButton(onClick = { model.acknowledgeSyncIssue(); model.refresh() }, enabled = !state.busy && !state.refreshing, modifier = Modifier.size(48.dp)) {
        if (state.busy || state.refreshing || state.pendingChanges > 0) CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = "Syncing" }, strokeWidth = 2.dp)
        else BadgedBox(badge = { if (attention) Badge { Text("!") } }) {
            Icon(Icons.Default.Refresh, if (attention) "Refresh temperatures; a change could not be confirmed" else "Refresh temperatures", Modifier.size(20.dp),
                tint = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable fun InlineError(state: UiState) {
    if (state.error && state.message != null) Text(state.message, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable private fun Welcome(model: AppState, state: UiState) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { RadiatorMark(); Text("evobuddy", style = MaterialTheme.typography.titleLarge, letterSpacing = (-0.8).sp) } }
        item { Text("Connect Evohome", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(8.dp)); Text("Sign in with your European Total Connect Comfort account.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { InlineError(state) }
        item { OutlinedTextField(email, { email = it }, label = { Text("TCC email") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)) }
        item { OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } }) }
        item { Button(onClick = { val secret = password; password = ""; model.login(email, secret) }, enabled = !state.busy && email.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Sign in") } }
        if (state.connected) item { OutlinedButton(onClick = model::refresh, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Retry loading my home") } }
        item { TextButton(onClick = model::enterDemo, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Explore the demo") } }
        item { Hint("Connects directly to Resideo. Your password is never saved; session tokens are encrypted on this phone.\n\nIndependent app. Not affiliated with Honeywell or Resideo.") }
    }
}
@Composable fun TemperatureControl(value: Double, room: Room, onChange: (Double) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = { onChange((value - room.step).coerceAtLeast(room.min)) }, enabled = value > room.min, modifier = Modifier.size(52.dp)) { Text("−", fontSize = 30.sp) }
            Text(temp(value), fontSize = 48.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp)
            FilledTonalIconButton(onClick = { onChange((value + room.step).coerceAtMost(room.max)) }, enabled = value < room.max, modifier = Modifier.size(52.dp)) { Text("+", fontSize = 28.sp) }
        }
        if (room.max > room.min) Slider(
            value = value.toFloat().coerceIn(room.min.toFloat(), room.max.toFloat()),
            onValueChange = { raw -> onChange((room.min + kotlin.math.round((raw - room.min) / room.step) * room.step).coerceIn(room.min, room.max)) },
            valueRange = room.min.toFloat()..room.max.toFloat(),
            steps = (((room.max - room.min) / room.step).toInt() - 1).coerceIn(0, 1000),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Target temperature" }
        )
    }
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun RoomSheet(model: AppState, state: UiState, room: Room, dismiss: () -> Unit, editSchedule: () -> Unit) {
    val home = state.home!!
    var value by remember(room.id) { mutableDoubleStateOf((room.target ?: 20.0).coerceIn(room.min, room.max)) }
    var duration by remember { mutableStateOf("1 hour") }
    var custom by remember { mutableStateOf(Instant.now().plusSeconds(3600).atZone(home.zone).withSecond(0).withNano(0)) }
    var shortcut by remember { mutableStateOf(false) }
    val end = when (duration) { "30 min" -> Instant.now().plusSeconds(1800); "1 hour" -> Instant.now().plusSeconds(3600); "2 hours" -> Instant.now().plusSeconds(7200); "Next change" -> room.schedule?.next(Instant.now(), home.zone)?.first; "Custom" -> custom.toInstant(); else -> null }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .85f
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(room.name, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TemperatureBadge(room.measured, false)
                            TemperatureBadge(room.target, true)
                        }
                    }
                    if (!room.available) Hint("Room unavailable")
                }
                item { TemperatureControl(value, room) { value = it } }
                item { Text("Duration", style = MaterialTheme.typography.titleSmall); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("30 min", "1 hour", "2 hours", "Next change", "Custom", "Until changed").forEach { label -> FilterChip(selected = duration == label, onClick = { duration = label }, label = { Text(if (label == "Until changed") "Hold…" else label) }, enabled = when (label) { "Until changed" -> "PermanentOverride" in room.allowed; "Next change" -> room.schedule != null && "TemporaryOverride" in room.allowed; else -> "TemporaryOverride" in room.allowed }) } } }
                if (duration == "Custom") item { DateTimeChoice(custom, home.zone) { custom = it } }
                if (room.mode != "FollowSchedule") item { OutlinedButton(onClick = { model.override(room, null, null, true); dismiss() }, enabled = !state.busy && "FollowSchedule" in room.allowed, modifier = Modifier.fillMaxWidth()) { Text("Resume schedule") } }
            }
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (end != null) {
                    val scheduled = scheduledTargetAt(room, home, end)
                    ScheduleTransition(value, scheduled, timeLabel(end, home.zone))
                    if (home.mode !in listOf("Auto", "AutoWithReset")) Hint("${modeLabel(home.mode)} mode also applies.")
                } else if (duration == "Until changed") Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, Modifier.size(16.dp)); Text("Until changed", style = MaterialTheme.typography.labelMedium)
                }
                Button(onClick = { model.override(room, value, end); dismiss() }, enabled = !state.busy && room.available && (duration == "Until changed" || end != null && end > Instant.now()), modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(if (duration == "Until changed") "Set permanent hold" else "Set ${temp(value)} until ${end?.let { timeLabel(it, home.zone) } ?: "…"}") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { TextButton(onClick = editSchedule) { Text("Edit schedule") }; TextButton(onClick = { shortcut = true }) { Text("Save a shortcut") } }
            }
        }
    }
    if (shortcut) ShortcutDialog(model, state, room, value) { shortcut = false }
}
@Composable private fun TemperatureBadge(value: Double?, target: Boolean) {
    val ink = if (target) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(color = if (target) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(14.dp), modifier = Modifier.clearAndSetSemantics { contentDescription = "${if (target) "Target" else "Current"} temperature ${temp(value)}" }) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Canvas(Modifier.size(14.dp)) {
                val s = size.width / 16f
                if (target) {
                    drawCircle(ink, 4.8f * s, style = Stroke(1.3f * s))
                    drawCircle(ink, 1.7f * s)
                    drawLine(ink, Offset(8*s, 0f), Offset(8*s, 4*s), 1.3f*s); drawLine(ink, Offset(8*s, 12*s), Offset(8*s, 16*s), 1.3f*s)
                    drawLine(ink, Offset(0f, 8*s), Offset(4*s, 8*s), 1.3f*s); drawLine(ink, Offset(12*s, 8*s), Offset(16*s, 8*s), 1.3f*s)
                } else {
                    drawRoundRect(ink, Offset(6*s, 1*s), Size(4*s, 10*s), CornerRadius(2*s), style = Stroke(1.3f*s))
                    drawLine(ink, Offset(8*s, 5*s), Offset(8*s, 12*s), 1.5f*s)
                    drawCircle(ink, 3*s, Offset(8*s, 12*s))
                }
            }
            Text(temp(value), style = MaterialTheme.typography.titleMedium, color = ink)
        }
    }
}
@Composable private fun ScheduleTransition(value: Double, scheduled: Double?, time: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f), shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Schedule resumes at $time${scheduled?.let { " at ${temp(it)}" } ?: ""}. The previous override is not restored." }) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(temp(value), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("Override", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(time, style = MaterialTheme.typography.labelMedium)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DateRange, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text(scheduled?.let(::temp) ?: "—", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                }
                Text("Schedule", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable fun DateTimeChoice(value: ZonedDateTime, zone: ZoneId, change: (ZonedDateTime) -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { DatePickerDialog(context, { _, y, m, d -> change(LocalDate.of(y, m + 1, d).atTime(value.toLocalTime()).atZone(zone)) }, value.year, value.monthValue - 1, value.dayOfMonth).show() }) { Text(value.format(DateTimeFormatter.ofPattern("d MMM yyyy"))) }
        OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> change(value.withHour(h).withMinute(m).withSecond(0).withNano(0)) }, value.hour, value.minute, true).show() }) { Text(value.format(DateTimeFormatter.ofPattern("HH:mm"))) }
    }
    Hint("Controller time · ${zone.id}")
}
@Composable fun TimeChoice(label: String, value: LocalTime, change: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedButton(onClick = {
        focus.clearFocus()
        keyboard?.hide()
        TimePickerDialog(context, { _, hour, minute -> change(LocalTime.of(hour, minute)) }, value.hour, value.minute, true).show()
    }, modifier = Modifier.semantics { contentDescription = label }) {
        Text("$label · ${value.format(DateTimeFormatter.ofPattern("HH:mm"))}")
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ModeDialog(model: AppState, state: UiState, dismiss: () -> Unit) {
    val home = state.home!!
    var chosen by remember { mutableStateOf(home.modes.firstOrNull { it.id == home.mode } ?: home.modes.firstOrNull()) }
    var timed by remember { mutableStateOf(false) }
    var units by remember { mutableStateOf("1") }
    val option = chosen
    val isHourly = option?.timing == "Duration"
    val count = units.toIntOrNull()
    val resolution = option?.let { durationMinutes(it.resolution) } ?: 1440
    val duration = count?.let { it * resolution.toLong() }
    val valid = !timed || count != null && count > 0 && duration!! <= durationMinutes(option!!.maximum)
    AlertDialog(onDismissRequest = dismiss, title = { Text("Home mode") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { home.modes.forEach { mode -> FilterChip(selected = option == mode, onClick = { chosen = mode; timed = !mode.permanent; units = "1" }, label = { Text(modeLabel(mode.id)) }) } }
            Text(when (option?.id) { "AutoWithReset" -> "Clears all room overrides and resumes their schedules."; "Auto" -> "Returns the system to Auto. The controller may clear temporary room overrides."; "HeatingOff" -> "Uses your controller’s Heating off mode. This affects every room."; "AutoWithEco" -> "Uses your controller’s Eco settings across the home."; else -> "Uses the ${option?.let { modeLabel(it.id) }} settings stored on your Evohome controller." }, style = MaterialTheme.typography.bodySmall)
            if (option?.temporary == true) { Row(verticalAlignment = Alignment.CenterVertically) { Switch(timed, { timed = it }); Text(if (timed) "End automatically" else "Until changed", Modifier.padding(start = 10.dp)) } }
            if (timed) OutlinedTextField(units, { units = it.filter(Char::isDigit).take(3) }, label = { Text(if (isHourly) "Steps of $resolution minutes" else "Steps of ${resolution / 1440} day(s)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), supportingText = { Text("Maximum ${durationMinutes(option!!.maximum) / resolution} steps") })
            if (!timed) Hint("This mode stays active until you change it.")
        }
    }, confirmButton = { TextButton(onClick = { if (option != null) { val end = if (!timed) null else if (isHourly) Instant.now().plusSeconds(duration!! * 60) else LocalDate.now(home.zone).atStartOfDay(home.zone).plusMinutes(duration!!).toInstant(); model.setMode(option, end); dismiss() } }, enabled = !state.busy && valid && option != null) { Text("Apply to home") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@Composable private fun ShortcutsScreen(model: AppState, state: UiState) {
    val home = state.home!!
    val context = LocalContext.current
    var adding by remember(home.key) { mutableStateOf(false) }
    var editing by remember(home.key) { mutableStateOf<Shortcut?>(null) }
    val shortcuts = state.shortcuts.filter { it.homeKey == home.key }
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Shortcuts", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium); SyncIndicator(model, state) } }
        if (state.error && state.message != null) item { InlineError(state) }
        item { Button(onClick = { adding = true }, enabled = home.rooms.isNotEmpty()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("New shortcut") } }
        if (shortcuts.isEmpty()) item { Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary); Text("No shortcuts yet", style = MaterialTheme.typography.titleMedium) } } }
        items(shortcuts, key = { it.id }) { shortcut ->
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(shortcut.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { editing = shortcut }, enabled = !state.busy && home.rooms.isNotEmpty()) { Icon(Icons.Default.Edit, "Edit ${shortcut.name}") }
                        IconButton(onClick = { model.deleteShortcut(shortcut.id) }, enabled = !state.busy) { Icon(Icons.Default.Delete, "Delete ${shortcut.name}") }
                    }
                    Hint("${shortcut.roomIds.joinToString(", ") { id -> home.rooms.firstOrNull { it.id == id }?.name ?: "Room removed" }} · ${temp(shortcut.temperature)} · ${shortcut.endTime?.let { "until ${endTimeLabel(shortcut.expiry(home, Instant.now()), home, Instant.now())}" } ?: "${shortcut.minutes} min"}")
                    state.shortcutRuns[shortcut.id].orEmpty().filter { it.status.startsWith("Failed") }.forEach { result ->
                        Text("${result.name}: ${result.status}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(onClick = { model.runShortcut(shortcut) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Run shortcut") }
                    if (HomeActions.canPin(context)) TextButton(onClick = { HomeActions.pin(context, shortcut) }) { Text("Add to home screen") }
                }
            }
        }
    }
    if (adding && home.rooms.isNotEmpty()) ShortcutDialog(model, state, home.rooms.first(), 20.0) { adding = false }
    editing?.let { shortcut ->
        val room = home.rooms.firstOrNull { it.id in shortcut.roomIds } ?: home.rooms.firstOrNull()
        if (room != null) ShortcutDialog(model, state, room, shortcut.temperature, shortcut) { editing = null }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ShortcutDialog(model: AppState, state: UiState, initialRoom: Room, initialTemp: Double, existing: Shortcut? = null, dismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var selectedIds by remember { mutableStateOf(existing?.roomIds?.toSet() ?: setOf(initialRoom.id)) }
    val home = state.home!!
    val selected = home.rooms.filter { it.id in selectedIds }
    val supported = sharedTemperatures(selected)
    var temperature by remember { mutableDoubleStateOf(initialTemp) }
    var minutes by remember { mutableStateOf((existing?.minutes ?: 60).toString()) }
    var clockMode by remember { mutableStateOf(existing?.endTime != null) }
    var clockTime by remember { mutableStateOf(existing?.endTime?.let(LocalTime::parse) ?: LocalTime.now(home.zone).plusHours(1).withSecond(0).withNano(0)) }
    LaunchedEffect(selectedIds) {
        if (supported.isNotEmpty() && supported.none { kotlin.math.abs(it - temperature) < .001 }) temperature = supported.minBy { kotlin.math.abs(it - temperature) }
    }
    val control = supported.firstOrNull()?.let { first -> initialRoom.copy(min = first, max = supported.last(), step = supported.getOrNull(1)?.minus(first) ?: initialRoom.step) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (existing == null) "New shortcut" else "Edit shortcut") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OutlinedTextField(name, { name = it.take(60) }, label = { Text("Name") }, placeholder = { Text("Evening heating") }, singleLine = true) }
            item { Text("Rooms", style = MaterialTheme.typography.titleSmall) }
            items(home.rooms, key = { it.id }) { room ->
                Row(Modifier.fillMaxWidth().clickable { selectedIds = if (room.id in selectedIds) selectedIds - room.id else selectedIds + room.id }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(room.id in selectedIds, { checked -> selectedIds = if (checked) selectedIds + room.id else selectedIds - room.id }, modifier = Modifier.semantics { contentDescription = "Include ${room.name}" })
                    Text(room.name)
                }
            }
            if (control != null) item { TemperatureControl(temperature, control) { temperature = it } }
            else item { Hint(if (selected.isEmpty()) "Select at least one room." else "These rooms have no common supported temperature.") }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !clockMode, onClick = { clockMode = false }, label = { Text("Duration") })
                FilterChip(selected = clockMode, onClick = { clockMode = true }, label = { Text("End time") })
            } }
            if (!clockMode) item { OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, label = { Text("Duration in minutes") }, supportingText = { Text("1–1440 minutes") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
            else item {
                TimeChoice("End time", clockTime) { clockTime = it }
                Hint("Ends ${endTimeLabel(nextClockTime(Instant.now(), home.zone, clockTime), home, Instant.now())} · ${home.zone.id}")
            }
            item { Hint("Afterwards, each room resumes its schedule. Existing overrides are replaced and won’t be restored.") }
        }
    }, confirmButton = { TextButton(onClick = { model.saveShortcut(name, selected.map { it.id }, temperature, if (clockMode) minutes.toIntOrNull() ?: 60 else minutes.toInt(), if (clockMode) clockTime.toString() else null, existing?.id); dismiss() }, enabled = name.isNotBlank() && (clockMode || minutes.toIntOrNull() in 1..1440) && selected.isNotEmpty() && selected.all { it.validTemperature(temperature) && "TemporaryOverride" in it.allowed }) { Text(if (existing == null) "Save shortcut" else "Save changes") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun SettingsScreen(model: AppState, state: UiState) {
    var signOut by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Settings", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium); SyncIndicator(model, state) } }
        item { SettingsCard("Home") { state.snapshot!!.homes.forEach { home -> Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { model.selectHome(home.key) }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = state.home?.key == home.key, onClick = { model.selectHome(home.key) }, enabled = !state.busy); Column { Text(home.name); Hint(home.zoneId) } } } } }
        item { SettingsCard("Appearance") { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("System", "Light", "Dark").forEach { FilterChip(selected = state.theme == it, onClick = { model.setTheme(it) }, label = { Text(it) }) } } } }
        item { SettingsCard("Connection") { Text(if (state.demo) "Demo mode" else "European Total Connect Comfort"); if (state.demo) Hint("Sample data. No devices connected."); Spacer(Modifier.height(10.dp)); OutlinedButton(onClick = { signOut = true }, enabled = !state.busy) { Text(if (state.demo) "Connect TCC account" else "Disconnect account") } } }
        item { SettingsCard("Privacy & storage") { Hint("Your password is never stored. Session tokens are encrypted with Android Keystore. Room readings, schedules, backups and shortcuts stay in this app’s private storage. Android backup is disabled. Disconnecting clears account data from this device.") } }
        item {
            Row(Modifier.fillMaxWidth().clickable { uriHandler.openUri("https://evobuddy.datatra.sh") }.semantics { contentDescription = "Open EvoBuddy website" }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                RadiatorMark()
                Column { Text("EvoBuddy", style = MaterialTheme.typography.titleMedium); Hint("Version 0.1.10 · independent Evohome app") }
            }
            Spacer(Modifier.height(10.dp))
            Text("Not affiliated with Honeywell or Resideo.", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
    if (signOut) AlertDialog(onDismissRequest = { signOut = false }, title = { Text(if (state.demo) "Connect your home?" else "Disconnect account?") }, text = { Text("This clears saved rooms, schedules, backups and shortcuts on this device. Your controller’s settings stay as they are.") }, confirmButton = { TextButton(onClick = { model.logout(); signOut = false }) { Text("Continue") } }, dismissButton = { TextButton(onClick = { signOut = false }) { Text("Cancel") } })
}
@Composable private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); content() } }
}
