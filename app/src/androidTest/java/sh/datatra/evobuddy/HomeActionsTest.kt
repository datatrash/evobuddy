package sh.datatra.evobuddy

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Build
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue

/** Real Android launcher/widget plumbing using sample rooms only. */
class HomeActionsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity
    private var host: AppWidgetHost? = null
    private var initialIntent: Intent? = null
    @After fun cleanup() {
        initialIntent?.let { original -> InstrumentationRegistry.getInstrumentation().runOnMainSync { context.intent = original } }
        host?.stopListening(); host?.deleteHost()
    }
    @Test fun widgetsAndLauncherUseCurrentSavedSettingsAndRoomLinksDoNotChangeHeating() {
        assumeTrue(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        initialIntent = Intent(context.intent)
        lateinit var model: AppState
        compose.runOnIdle {
            model = ViewModelProvider(context)[AppState::class.java]
            model.logout(); model.enterDemo()
            model.saveShortcut("Morning heating", listOf("living", "kitchen"), 20.0, 60)
        }
        compose.waitForIdle()
        val shortcut = model.state.value.shortcuts.single()
        val home = model.state.value.home!!
        val manager = context.getSystemService(ShortcutManager::class.java)
        assertTrue(manager.dynamicShortcuts.any { it.id == "saved:${shortcut.id}" })
        val widgetManager = AppWidgetManager.getInstance(context)
        val widgetHost = AppWidgetHost(context, 7341).also { host = it }
        fun allocate(provider: Class<*>): Int {
            val id = widgetHost.allocateAppWidgetId()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            try { assertTrue(widgetManager.bindAppWidgetIdIfAllowed(id, ComponentName(context, provider))) }
            finally { automation.dropShellPermissionIdentity() }
            assertEquals(0, widgetManager.getAppWidgetInfo(id).updatePeriodMillis)
            return id
        }
        val shortcutWidget = allocate(ShortcutWidgetProvider::class.java)
        val roomWidget = allocate(RoomWidgetProvider::class.java)
        widgetHost.startListening()
        compose.runOnIdle {
            context.startActivity(Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, shortcutWidget))
        }
        compose.onNodeWithText("Choose a shortcut").assertExists()
        compose.onNodeWithText("Morning heating").performClick()
        compose.waitForIdle()
        assertEquals(HomeTarget("shortcut", home.key, shortcut.id), HomeActions.target(context, shortcutWidget))
        HomeActions.bind(context, roomWidget, HomeTarget("room", home.key, "kitchen"))
        fun widgetTitle(id: Int): String {
            var title = ""
            compose.runOnIdle {
                val view = widgetHost.createView(context, id, widgetManager.getAppWidgetInfo(id))
                title = view.findViewById<TextView>(R.id.widget_title).text.toString()
                val density = context.resources.displayMetrics.density
                val width = ((if (id == roomWidget) 100 else 200) * density).toInt(); val height = (100 * density).toInt()
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                java.io.File(context.getExternalFilesDir(null), "widget-${title.replace(' ', '-')}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            return title
        }
        fun clickWidget(id: Int) = compose.runOnIdle {
            val view = widgetHost.createView(context, id, widgetManager.getAppWidgetInfo(id))
            assertTrue(view.findViewById<android.view.View>(R.id.widget_button).performClick())
        }
        assertEquals("Morning heating", widgetTitle(shortcutWidget))
        assertEquals("19.4°", widgetTitle(roomWidget))
        val beforeRoom = model.state.value.home!!.rooms
        clickWidget(roomWidget)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Current temperature 19.4°").assertExists()
        assertEquals(beforeRoom, model.state.value.home!!.rooms)
        compose.runOnIdle { model.saveShortcut("Evening heating", listOf("kitchen"), 18.5, 90, "22:30", shortcut.id) }
        assertEquals("Evening heating", widgetTitle(shortcutWidget))
        assertEquals("Evening heating", manager.dynamicShortcuts.single { it.id == "saved:${shortcut.id}" }.shortLabel.toString())
        val pauses = java.util.concurrent.atomic.AtomicInteger()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) pauses.incrementAndGet() }
        compose.runOnIdle { context.lifecycle.addObserver(observer) }
        clickWidget(shortcutWidget)
        compose.waitUntil(10000) { HomeActions.isAcknowledged(shortcut.id) }
        assertEquals("Evening heating", widgetTitle(shortcutWidget))
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.home!!.rooms.first { it.id == "kitchen" }.target == 18.5 }
        assertEquals(0, pauses.get()) // Shortcut widgets dispatch to a service, without launching an activity.
        compose.runOnIdle { context.lifecycle.removeObserver(observer) }
        val notifications = context.getSystemService(android.app.NotificationManager::class.java)
        compose.waitUntil(10000) { !Commands.foreground && notifications.activeNotifications.none { it.id == 1402 } }
        assertFalse(HomeActions.isAcknowledged(shortcut.id))
        assertEquals(android.app.NotificationManager.IMPORTANCE_LOW, notifications.getNotificationChannel("changes").importance)
        val launcherIntent = manager.dynamicShortcuts.single { it.id == "saved:${shortcut.id}" }.intent!!
        assertEquals(ShortcutActionActivity::class.java.name, launcherIntent.component!!.className)
        compose.runOnIdle {
            model.saveShortcut("Evening heating", listOf("kitchen"), 19.0, 90, "22:30", shortcut.id)
            context.startActivity(launcherIntent)
        }
        compose.waitUntil(10000) { model.state.value.home!!.rooms.first { it.id == "kitchen" }.target == 19.0 }
        compose.waitUntil(10000) { !Commands.foreground && notifications.activeNotifications.none { it.id == 1402 } }
        assertEquals(beforeRoom.first { it.id == "living" }, model.state.value.home!!.rooms.first { it.id == "living" })
        val appliedUntil = model.state.value.home!!.rooms.first { it.id == "kitchen" }.until
        assertNotNull(appliedUntil)
        // ActivityScenario identifies its activity by the original MAIN intent.
        // The real widget delivery replaces it with VIEW; restore test identity.
        compose.runOnIdle { context.intent = Intent(initialIntent) }
        compose.runOnIdle { model.deleteShortcut(shortcut.id) }
        assertFalse(manager.dynamicShortcuts.any { it.id == "saved:${shortcut.id}" })
        assertEquals("Choose an action", widgetTitle(shortcutWidget))
        compose.runOnIdle { model.logout() }
        assertTrue(manager.dynamicShortcuts.isEmpty())
        assertEquals("Connect Evohome", widgetTitle(roomWidget))
    }
}
