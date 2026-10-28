package sh.datatra.evobuddy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.InputDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeTrue
import android.os.Build

class RoomReorderTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun resetEmulatorSession() {
        assumeTrue(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        compose.runOnIdle { ViewModelProvider(compose.activity)[AppState::class.java].logout() }
        compose.waitForIdle()
    }
    @Test fun dragToLastRoomAutoScrollsAndPersists() {
        lateinit var model: AppState
        compose.onNodeWithText("Explore the demo").performClick()
        compose.onNode(hasText("Home") and hasClickAction()).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("room-living").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[AppState::class.java] }
        val grid = compose.onNodeWithTag("rooms-grid")
        val bounds = grid.fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("room-living").fetchSemanticsNode().boundsInRoot
        val edge = Offset(bounds.width * .75f, bounds.height - 100f)
        fun owner(view: View): View? {
            if (view.javaClass.name.endsWith(".AndroidComposeView")) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) owner(view.getChildAt(index))?.let { return it }
            return null
        }
        val view = checkNotNull(owner(compose.activity.window.decorView))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        var touch = card.center
        fun send(action: Int) {
            instrumentation.runOnMainSync {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, touch.x, touch.y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        try {
            send(MotionEvent.ACTION_DOWN)
            Thread.sleep(700); compose.mainClock.advanceTimeBy(700)
            touch = Offset(bounds.left + edge.x, bounds.top + edge.y)
            send(MotionEvent.ACTION_MOVE)
            for (frame in 0..240) {
                Thread.sleep(16); compose.mainClock.advanceTimeBy(16)
                if (model.state.value.roomOrder[model.state.value.home!!.key]?.lastOrNull() == "living") break
            }
        } finally { send(MotionEvent.ACTION_UP) }
        compose.waitForIdle()
        assertEquals("living", model.state.value.roomOrder[model.state.value.home!!.key]?.lastOrNull())
        compose.runOnIdle {
            val restored = AppState(compose.activity.application).state.value
            assertEquals("living", restored.roomOrder.getValue(restored.home!!.key).last())
            assertEquals(6, restored.roomOrder.getValue(restored.home!!.key).distinct().size)
        }
    }
}
