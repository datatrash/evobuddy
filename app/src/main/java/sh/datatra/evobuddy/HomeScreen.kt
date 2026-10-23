package sh.datatra.evobuddy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant

private fun LazyGridItemInfo.bounds() = Rect(offset.x.toFloat(), offset.y.toFloat(), (offset.x + size.width).toFloat(), (offset.y + size.height).toFloat())

@Composable fun HomeScreen(model: AppState, state: UiState, openRoom: (Room) -> Unit, openMode: () -> Unit) {
    val home = state.home!!
    val rooms = orderedRooms(home, state.roomOrder[home.key].orEmpty(), state.favourites)
    val currentRooms by rememberUpdatedState(rooms)
    val grid = rememberLazyGridState()
    val haptics = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    var dragging by remember(home.key) { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var anchor by remember { mutableStateOf(Offset.Zero) }
    var lastTarget by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(home.key) { while (isActive) { now = Instant.now(); delay(30_000) } }
    fun moveOverTarget() {
        val id = dragging ?: return
        val target = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key != id && currentRooms.any { it.id == item.key } && item.bounds().contains(pointer)
        }?.key as? String
        if (target != null && target != lastTarget) {
            val index = grid.firstVisibleItemIndex
            val offset = grid.firstVisibleItemScrollOffset
            model.moveRoom(id, target)
            grid.requestScrollToItem(index, offset)
            lastTarget = target
        } else if (target == null) lastTarget = null
    }
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        // Observe viewport changes outside composition: layoutInfo changes on
        // measurement and must not invalidate the grid's own composition.
        snapshotFlow {
            val top = grid.layoutInfo.viewportStartOffset.coerceAtLeast(0)
            val bottom = grid.layoutInfo.viewportEndOffset
            when {
                pointer.y < top + edge -> -(top + edge - pointer.y).coerceAtMost(edge) * .25f
                pointer.y > bottom - edge -> (pointer.y - bottom + edge).coerceAtMost(edge) * .25f
                else -> 0f
            }
        }.collectLatest { speed ->
            if (speed != 0f) while (isActive) {
                grid.scrollBy(speed); moveOverTarget(); delay(16)
            }
        }
    }
    fun finishDrag() { if (dragging != null) model.persistRoomOrder(); dragging = null; lastTarget = null }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2), state = grid, userScrollEnabled = dragging == null,
        contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().testTag("rooms-grid").pointerInput(home.key) {
            detectDragGesturesAfterLongPress(
                onDragStart = { point ->
                    val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.bounds().contains(point) && currentRooms.any { room -> room.id == it.key } }
                    if (item != null) {
                        dragging = item.key as String; pointer = point
                        anchor = point - Offset(item.offset.x.toFloat(), item.offset.y.toFloat())
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onDrag = { change, delta -> if (dragging != null) { change.consume(); pointer += delta; moveOverTarget() } },
                onDragEnd = ::finishDrag, onDragCancel = ::finishDrag
            )
        }
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { RadiatorMark(); Text("evobuddy", style = MaterialTheme.typography.titleLarge, letterSpacing = (-0.8).sp) }
                SyncIndicator(model, state)
            }
        }
        if (state.error && state.message != null) item(key = "error", span = { GridItemSpan(maxLineSpan) }) { InlineError(state) }
        item(key = "home", span = { GridItemSpan(maxLineSpan) }) { Text(home.name, style = MaterialTheme.typography.headlineMedium) }
        item(key = "mode", span = { GridItemSpan(maxLineSpan) }) {
            Surface(onClick = openMode, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Home, null, tint = MaterialTheme.colorScheme.secondary)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) { Text(modeLabel(home.mode), style = MaterialTheme.typography.titleMedium); Hint(home.modeUntil?.let { "Until ${timeLabel(it, home.zone)}" } ?: "Change home mode") }
                    Icon(Icons.Default.KeyboardArrowDown, "Change home mode")
                }
            }
        }
        item(key = "rooms-label", span = { GridItemSpan(maxLineSpan) }) {
            Eyebrow("Rooms")
        }
        items(rooms, key = { it.id }, contentType = { "room" }) { room ->
            val isDragging = dragging == room.id
            Box(Modifier.testTag("room-${room.id}").zIndex(if (isDragging) 1f else 0f).graphicsLayer {
                if (isDragging) {
                    val current = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == room.id }
                    translationX = pointer.x - anchor.x - (current?.offset?.x ?: 0)
                    translationY = pointer.y - anchor.y - (current?.offset?.y ?: 0)
                    shadowElevation = 12.dp.toPx(); scaleX = 1.025f; scaleY = 1.025f
                }
            }.semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Move earlier") {
                        val ids = currentRooms.map { it.id }; val index = ids.indexOf(room.id)
                        if (index > 0) { model.moveRoom(room.id, ids[index - 1]); model.persistRoomOrder(); true } else false
                    },
                    CustomAccessibilityAction("Move later") {
                        val ids = currentRooms.map { it.id }; val index = ids.indexOf(room.id)
                        if (index in 0 until ids.lastIndex) { model.moveRoom(room.id, ids[index + 1]); model.persistRoomOrder(); true } else false
                    }
                )
            }) { RoomCard(room, home, now, "${home.key}:${room.id}" in state.favourites, { openRoom(room) }, { model.toggleFavourite(room) }) }
        }
    }
}

@Composable private fun RoomCard(room: Room, home: Home, now: Instant, favourite: Boolean, open: () -> Unit, toggleFavourite: () -> Unit) {
    val override = room.mode != "FollowSchedule"
    val next = nextRoomEvent(room, home, now)
    Surface(onClick = open, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, if (override) MaterialTheme.colorScheme.primary.copy(alpha = .45f) else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = if (override) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) { RadiatorMark(Modifier.padding(9.dp).size(20.dp), if (override) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary) }
                Spacer(Modifier.weight(1f))
                val grip = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(Modifier.size(12.dp, 20.dp).semantics { contentDescription = "Hold to reorder ${room.name}" }) {
                    for (x in listOf(.25f, .75f)) for (y in listOf(.25f, .5f, .75f)) drawCircle(grip, 1.4.dp.toPx(), Offset(size.width * x, size.height * y))
                }
                IconToggleButton(checked = favourite, onCheckedChange = { toggleFavourite() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Star, if (favourite) "Remove ${room.name} from favourites" else "Favourite ${room.name}", tint = if (favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(19.dp)) }
            }
            Text(room.name, style = MaterialTheme.typography.titleSmall)
            Text(temp(room.measured), fontSize = 36.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.6).sp)
            Text("Target ${temp(room.target)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(if (!room.available) "Room unavailable" else if (override) room.until?.let { "Until ${timeLabel(it, home.zone)}" } ?: "Permanent hold" else "On schedule", style = MaterialTheme.typography.labelSmall, color = if (override) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
            Hint(next?.let {
                val target = it.temperature?.let(::temp) ?: "schedule"
                if (it.resumesSchedule) "Resumes $target at ${timeLabel(it.at, home.zone)}" else "Next $target at ${timeLabel(it.at, home.zone)}"
            } ?: if (room.mode == "PermanentOverride") "Until changed" else "Tap to manage", Modifier.heightIn(min = 30.dp))
        }
    }
}
