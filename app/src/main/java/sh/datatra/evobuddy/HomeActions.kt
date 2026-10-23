package sh.datatra.evobuddy

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.RemoteViews
import java.util.concurrent.ConcurrentHashMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

data class HomeTarget(val kind: String, val home: String, val id: String) {
    fun uri(): Uri = Uri.Builder().scheme("evobuddy").authority(kind).appendPath(home).appendPath(id).build()
    companion object {
        fun parse(uri: Uri?): HomeTarget? = uri?.takeIf { it.scheme == "evobuddy" && it.authority in listOf("shortcut", "room") && it.pathSegments.size == 2 }
            ?.let { HomeTarget(it.authority!!, it.pathSegments[0], it.pathSegments[1]) }
    }
}

/** Launcher metadata only: no network, timers, services or temperature snapshots. */
object HomeActions {
    private val acknowledgements = ConcurrentHashMap<String, Long>()
    fun isAcknowledged(id: String) = (acknowledgements[id] ?: 0L) > SystemClock.elapsedRealtime()
    fun acknowledge(context: Context, id: String): Long {
        val until = SystemClock.elapsedRealtime() + 1600
        acknowledgements[id] = until
        updateWidgets(context)
        Handler(Looper.getMainLooper()).postDelayed({
            if (acknowledgements[id] == until) { acknowledgements.remove(id); updateWidgets(context.applicationContext) }
        }, 1600)
        return until
    }
    fun catalog(context: Context): Pair<List<Home>, List<Shortcut>> {
        val prefs = context.getSharedPreferences("evobuddy", Context.MODE_PRIVATE)
        return runCatching {
            val snapshot = if (prefs.getBoolean("demo", false)) prefs.getString("cache", null)?.let { snapshotFromJson(JSONObject(it)) } ?: demoSnapshot() else {
                if (BackgroundSync.client(context).session == null) return emptyList<Home>() to emptyList()
                snapshotFromJson(JSONObject(prefs.getString("cache", null) ?: return emptyList<Home>() to emptyList()))
            }
            val shortcuts = JSONArray(prefs.getString("shortcuts", "[]")).objects().map(Shortcut::parse)
            snapshot.homes to shortcuts.filter { shortcut -> snapshot.homes.any { it.key == shortcut.homeKey } }
        }.getOrDefault(emptyList<Home>() to emptyList())
    }
    fun intent(context: Context, target: HomeTarget) = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW).setData(target.uri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    private fun info(context: Context, shortcut: Shortcut) = ShortcutInfo.Builder(context, "saved:${shortcut.id}")
        .setActivity(ComponentName(context, MainActivity::class.java))
        .setShortLabel(shortcut.name.take(40)).setLongLabel(shortcut.name)
        .setIcon(Icon.createWithResource(context, R.drawable.ic_radiator))
        .setIntent(Intent(context, ShortcutActionActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(HomeTarget("shortcut", shortcut.homeKey, shortcut.id).uri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)).build()
    fun canPin(context: Context) = context.getSystemService(ShortcutManager::class.java).isRequestPinShortcutSupported
    fun pin(context: Context, shortcut: Shortcut) {
        runCatching { context.getSystemService(ShortcutManager::class.java).requestPinShortcut(info(context, shortcut), null) }
    }
    fun sync(context: Context) {
        val (_, shortcuts) = catalog(context)
        runCatching {
            val manager = context.getSystemService(ShortcutManager::class.java)
            val valid = shortcuts.map { "saved:${it.id}" }.toSet()
            val old = (manager.dynamicShortcuts + manager.pinnedShortcuts).distinctBy { it.id }
            val removed = old.filter { it.id !in valid }.map { it.id }
            val disabled = old.filter { it.id in valid && !it.isEnabled }.map { it.id }
            if (removed.isNotEmpty()) manager.disableShortcuts(removed, "Shortcut unavailable. Open EvoBuddy.")
            if (disabled.isNotEmpty()) manager.enableShortcuts(disabled)
            if (!manager.isRateLimitingActive) {
                val changed = shortcuts.filter { shortcut -> old.any { it.id == "saved:${shortcut.id}" } }.map { info(context, it) }
                if (changed.isNotEmpty()) manager.updateShortcuts(changed)
                manager.dynamicShortcuts = shortcuts.take(manager.maxShortcutCountPerActivity).map { info(context, it) }
            }
        }
        updateWidgets(context)
    }
    private fun bindings(context: Context) = context.getSharedPreferences("home-widgets", Context.MODE_PRIVATE)
    fun disconnect(context: Context) { bindings(context).edit().clear().commit(); sync(context) }
    fun bind(context: Context, widgetId: Int, target: HomeTarget) {
        bindings(context).edit().putString(widgetId.toString(), target.uri().toString()).commit()
        updateWidget(context, widgetId)
    }
    fun target(context: Context, widgetId: Int) = runCatching { HomeTarget.parse(bindings(context).getString(widgetId.toString(), null)?.let(Uri::parse)) }.getOrNull()
    fun deleteBinding(context: Context, widgetId: Int) { bindings(context).edit().remove(widgetId.toString()).apply() }
    fun updateWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        listOf(ShortcutWidgetProvider::class.java, RoomWidgetProvider::class.java).forEach { provider ->
            manager.getAppWidgetIds(ComponentName(context, provider)).forEach { updateWidget(context, it) }
        }
    }
    fun updateRoomWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, RoomWidgetProvider::class.java)).forEach { updateWidget(context, it) }
    }
    fun updateWidget(context: Context, widgetId: Int) {
        val (homes, shortcuts) = catalog(context)
        val target = target(context, widgetId)
        val home = homes.find { it.key == target?.home }
        val shortcut = shortcuts.find { target?.kind == "shortcut" && it.id == target.id && it.homeKey == home?.key }
        val room = home?.rooms?.find { target?.kind == "room" && it.id == target.id }
        val title = shortcut?.name ?: room?.measured?.let(::temp) ?: if (room != null) "—°" else if (homes.isEmpty()) "Connect Evohome" else "Choose an action"
        val subtitle = when {
            shortcut != null -> "${temp(shortcut.temperature)} · ${shortcut.endTime?.let { "until $it" } ?: "${shortcut.minutes} min"}"
            room != null -> room.name
            homes.isEmpty() -> "Open EvoBuddy"
            else -> "Tap to configure"
        }
        val click = when {
            shortcut != null || room != null -> intent(context, target!!)
            homes.isEmpty() -> Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            else -> Intent(context, WidgetConfigurationActivity::class.java)
                .setData(Uri.parse("evobuddy://configure/$widgetId"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val roomProvider = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)?.provider?.className == RoomWidgetProvider::class.java.name
        val views = RemoteViews(context.packageName, if (roomProvider) R.layout.room_widget else R.layout.home_widget)
        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_subtitle, subtitle)
        if (!roomProvider) views.setImageViewResource(R.id.widget_icon, if (shortcut != null && isAcknowledged(shortcut.id)) R.drawable.ic_widget_started else R.drawable.ic_radiator)
        views.setContentDescription(R.id.widget_button, "$title. $subtitle")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = if (shortcut != null)
            PendingIntent.getForegroundService(context, widgetId, Intent(context, CommandService::class.java).setAction(Intent.ACTION_VIEW).setData(target!!.uri()), flags)
        else PendingIntent.getActivity(context, widgetId, click, flags)
        views.setOnClickPendingIntent(R.id.widget_button, pending)
        AppWidgetManager.getInstance(context).updateAppWidget(widgetId, views)
    }
}

open class BuddyWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { HomeActions.updateWidget(context, it) } }
    override fun onDeleted(context: Context, ids: IntArray) { ids.forEach { HomeActions.deleteBinding(context, it) } }
    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        oldIds.zip(newIds).forEach { (old, new) -> if (old != new) { HomeActions.target(context, old)?.let { HomeActions.bind(context, new, it) }; HomeActions.deleteBinding(context, old) } }
    }
}
class ShortcutWidgetProvider : BuddyWidgetProvider()
class RoomWidgetProvider : BuddyWidgetProvider()

class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        if (provider?.packageName != packageName) { finish(); return }
        val rooms = provider.className == RoomWidgetProvider::class.java.name
        val (homes, shortcuts) = HomeActions.catalog(this)
        fun choose(target: HomeTarget) {
            HomeActions.bind(this, widgetId, target)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)); finish()
        }
        setContent {
            EvoTheme(isSystemInDarkTheme()) {
                Surface(Modifier.fillMaxSize()) {
                    LazyColumn(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Text(if (rooms) "Choose a room" else "Choose a shortcut", style = MaterialTheme.typography.headlineMedium) }
                        homes.forEach { home ->
                            item { Text(home.name, style = MaterialTheme.typography.titleSmall) }
                            if (rooms) home.rooms.forEach { room -> item { OutlinedButton(onClick = { choose(HomeTarget("room", home.key, room.id)) }, Modifier.fillMaxWidth()) { Text(room.name) } } }
                            else shortcuts.filter { it.homeKey == home.key }.forEach { shortcut -> item { OutlinedButton(onClick = { choose(HomeTarget("shortcut", home.key, shortcut.id)) }, Modifier.fillMaxWidth()) { Text(shortcut.name) } } }
                        }
                        if (homes.isEmpty() || !rooms && shortcuts.isEmpty()) item {
                            Text(if (homes.isEmpty()) "Connect your account in EvoBuddy first." else "Create a shortcut in EvoBuddy first.")
                            TextButton(onClick = { startActivity(Intent(this@WidgetConfigurationActivity, MainActivity::class.java)); finish() }) { Text("Open EvoBuddy") }
                        }
                        item { TextButton(onClick = { finish() }) { Text("Cancel") } }
                    }
                }
            }
        }
    }
}
