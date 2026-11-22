package sh.datatra.evobuddy

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeTrue
import android.os.Build
import java.io.File
import android.widget.TimePicker
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewAction
import androidx.test.espresso.UiController
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.action.ViewActions.click

/** Uses sample data only; never signs into or changes a real heating installation. */
class DemoFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun resetEmulatorSession() {
        assumeTrue(Build.MODEL.startsWith("sdk_gphone") || Build.FINGERPRINT.contains("generic"))
        compose.runOnIdle { ViewModelProvider(compose.activity)[AppState::class.java].logout() }
        compose.waitForIdle()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(300)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun pickTime(hour: Int, minute: Int) {
        onView(isAssignableFrom(TimePicker::class.java)).perform(object : ViewAction {
            override fun getConstraints() = isAssignableFrom(TimePicker::class.java)
            override fun getDescription() = "Choose time in the native time picker"
            override fun perform(controller: UiController, view: android.view.View) {
                (view as TimePicker).apply { this.hour = hour; this.minute = minute }
                controller.loopMainThreadUntilIdle()
            }
        })
        onView(withText(android.R.string.ok)).perform(click())
    }
    @Test fun roomTemperaturesAndHigherSetpointsFitOnOneLine() {
        compose.onNodeWithText("Explore the demo").performClick()
        compose.onNode(hasText("Home") and hasClickAction()).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("room-living").fetchSemanticsNodes().isNotEmpty() }

        for (reading in listOf("${temp(20.1)} → ${temp(21.0)}", "${temp(19.4)} → ${temp(20.0)}")) {
            compose.onNodeWithText(reading, useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
                    val results = mutableListOf<TextLayoutResult>()
                    assertTrue(getLayout(results))
                    val layout = results.single()
                    assertEquals(reading, 1, layout.lineCount)
                    assertFalse("$reading is clipped", layout.hasVisualOverflow)
                }
        }
        compose.onNode(
            hasAnyAncestor(hasTestTag("room-living")) and hasText("Target ${temp(21.0)}"),
            useUnmergedTree = true
        ).assertDoesNotExist()
        screenshot("room-temperatures")

        compose.onNodeWithTag("rooms-grid").performScrollToNode(hasTestTag("room-bedroom"))
        compose.onNode(
            hasAnyAncestor(hasTestTag("room-bedroom")) and hasText("Target ${temp(17.0)}"),
            useUnmergedTree = true
        ).assertIsDisplayed()
    }

    @Test fun roomsOverridesMultiRoomShortcutsAndQuietSync() {
        screenshot("01-welcome")
        compose.onNodeWithText("Explore the demo").performClick()
        compose.onNode(hasText("Home") and hasClickAction()).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Living room").fetchSemanticsNodes().isNotEmpty() }
        lateinit var model: AppState
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[AppState::class.java] }
        compose.onNodeWithText("Live cloud data").assertDoesNotExist()
        compose.onNodeWithText("Demo data").assertDoesNotExist()
        compose.onNode(hasText("active overrides", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("Cached", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("Updated", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("Schedule", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("20.1° → 21.0°", useUnmergedTree = true).assertIsDisplayed()

        val grid = compose.onNodeWithTag("rooms-grid")
        val gridBounds = grid.fetchSemanticsNode().boundsInRoot
        val living = compose.onNodeWithTag("room-living").fetchSemanticsNode().boundsInRoot
        val kitchen = compose.onNodeWithTag("room-kitchen").fetchSemanticsNode().boundsInRoot
        val start = Offset(living.center.x - gridBounds.left, living.center.y - gridBounds.top)
        val end = Offset(kitchen.center.x - gridBounds.left, kitchen.center.y - gridBounds.top)
        grid.performTouchInput { down(start) }
        compose.mainClock.advanceTimeBy(700)
        grid.performTouchInput { moveTo(end, 300); up() }
        compose.waitUntil(10000) { model.state.value.roomOrder[model.state.value.home!!.key]?.firstOrNull() == "kitchen" }
        assertTrue(compose.onNodeWithTag("room-kitchen").fetchSemanticsNode().boundsInRoot.left < compose.onNodeWithTag("room-living").fetchSemanticsNode().boundsInRoot.left)
        compose.onNodeWithContentDescription("Favourite Living room").performClick()
        assertEquals("kitchen", model.state.value.roomOrder[model.state.value.home!!.key]!!.first())
        screenshot("02-home-light")

        val beforeSync = compose.onNodeWithTag("room-living").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithContentDescription("Refresh temperatures").performClick()
        compose.waitForIdle()
        assertEquals(beforeSync, compose.onNodeWithTag("room-living").fetchSemanticsNode().boundsInRoot.top, .1f)
        val lastActual = model.state.value.home!!.rooms
        compose.runOnIdle { compose.activity.getSharedPreferences("evobuddy", android.content.Context.MODE_PRIVATE).edit().putString("commandIssues", "{\"failed\":\"Could not finish this change\"}").commit() }
        compose.waitUntil(10000) { model.state.value.commandIssues.isNotEmpty() }
        compose.onNodeWithContentDescription("Refresh temperatures; a change could not be confirmed").assertExists()
        compose.onNodeWithText("Could not finish this change").assertDoesNotExist()
        assertEquals(lastActual, model.state.value.home!!.rooms)
        compose.onNodeWithContentDescription("Refresh temperatures; a change could not be confirmed").performClick()
        compose.waitUntil(10000) { model.state.value.commandIssues.isEmpty() && !model.state.value.refreshing }
        compose.onNodeWithText("Living room").performClick()
        compose.onNodeWithContentDescription("Target temperature").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(12f) }
        compose.onAllNodesWithText("12.0°").onFirst().assertExists()
        compose.onNodeWithContentDescription("Target temperature").performTouchInput { swipe(centerLeft, centerRight) }
        compose.onNodeWithContentDescription("Target temperature").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(21f) }
        compose.onNodeWithText("30 min").performClick()
        compose.onNode(hasContentDescription("Schedule resumes at", substring = true)).assertExists()
        compose.onNodeWithContentDescription("Current temperature 20.1°").assertExists()
        compose.onNodeWithContentDescription("Target temperature 21.0°").assertExists()
        compose.onNode(hasText("Live TCC", substring = true)).assertDoesNotExist()
        screenshot("03-room-override")
        compose.onNode(hasText("Set 21.0° until", substring = true)).performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.home!!.rooms.first { it.id == "living" }.mode == "TemporaryOverride" }
        compose.onNodeWithTag("room-living").assert(hasAnyDescendant(hasText("Resumes", substring = true)))
        compose.onNodeWithText("Applied in demo. No heating devices were changed.").assertDoesNotExist()
        compose.onNodeWithText("Living room").performClick()
        compose.onNodeWithText("Replaces existing override").assertDoesNotExist()
        compose.onNodeWithText("Edit schedule").performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("06:30"))
        compose.onNodeWithText("06:30").performClick()
        compose.onNodeWithContentDescription("Start time").performClick()
        pickTime(6, 40)
        compose.onNodeWithText("Start time · 06:40").assertExists()
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("Keep change").performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("Unsaved draft"))
        screenshot("04-schedule")
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("Review & save"))
        compose.onNodeWithText("Review & save").performClick()
        compose.onNodeWithText("Save to 1 room(s)").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.home!!.rooms.first { it.id == "living" }.schedule!!.days.first().first().let { it.temperature == 21.0 && it.minute == 400 } }
        compose.onNodeWithContentDescription("Back to rooms").performClick()
        compose.onNodeWithText("20.1° → 21.0°", useUnmergedTree = true).assertIsDisplayed()

        compose.onNodeWithText("Shortcuts", useUnmergedTree = true).performClick()
        compose.onNodeWithText("New shortcut").performClick()
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Evening heating")
        compose.onNodeWithContentDescription("Include Kitchen").performClick()
        compose.onNodeWithText("Save shortcut").performClick()
        compose.onNodeWithText("Evening heating").assertExists()
        assertEquals(listOf("living", "kitchen"), model.state.value.shortcuts.single().roomIds)
        compose.onNodeWithText("Run shortcut").performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.shortcutRuns.isNotEmpty() }
        val applied = model.state.value.home!!.rooms.filter { it.id in listOf("living", "kitchen") }
        assertTrue(applied.all { it.target == 20.0 && it.mode == "TemporaryOverride" })
        assertEquals(1, applied.map { it.until }.distinct().size)
        compose.onNodeWithText("Confirmed").assertDoesNotExist()
        compose.onNodeWithText("Sent to TCC. Waiting for the controller…").assertDoesNotExist()
        screenshot("05-shortcuts")

        compose.onNodeWithText("New shortcut").performClick()
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Until bedtime")
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("End time"))
        compose.onNodeWithText("End time", substring = false).performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasContentDescription("End time"))
        compose.onNodeWithContentDescription("End time").performClick()
        pickTime(23, 0)
        compose.onNodeWithText("End time · 23:00").assertExists()
        compose.onNode(hasSetTextAction() and hasText("End time")).assertDoesNotExist()
        screenshot("07-clock-shortcut")
        compose.onNodeWithText("Save shortcut").performClick()
        assertEquals("23:00", model.state.value.shortcuts.last().endTime)

        val savedClock = model.state.value.shortcuts.last()
        compose.onNodeWithContentDescription("Edit Until bedtime").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Name")).assertTextContains("Until bedtime")
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Discard this")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(savedClock, model.state.value.shortcuts.last())

        compose.onNodeWithContentDescription("Edit Until bedtime").performClick()
        compose.onNodeWithContentDescription("Include Living room").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Include Kitchen").assertIsOff().performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasContentDescription("Target temperature"))
        compose.onNodeWithContentDescription("Target temperature").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(18.5f) }
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasContentDescription("End time"))
        compose.onNodeWithText("End time · 23:00").assertExists()
        compose.onNodeWithContentDescription("End time").performClick()
        pickTime(22, 30)
        screenshot("08-edit-shortcut")
        compose.onNodeWithText("Save changes").performClick()
        val editedClock = model.state.value.shortcuts.last()
        assertEquals(savedClock.id, editedClock.id)
        assertEquals(2, model.state.value.shortcuts.size)
        assertEquals(listOf("kitchen"), editedClock.roomIds)
        assertEquals(18.5, editedClock.temperature, .0)
        assertEquals("22:30", editedClock.endTime)
        assertEquals(20.0, model.state.value.home!!.rooms.first { it.id == "kitchen" }.target!!, .0)

        compose.onNodeWithContentDescription("Edit Evening heating").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Include Living room").assertIsOn()
        compose.onNodeWithContentDescription("Include Kitchen").assertIsOn()
        compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToNode(hasText("Duration in minutes"))
        compose.onNode(hasSetTextAction() and hasText("Duration in minutes")).assertTextContains("60").performTextReplacement("90")
        compose.onNodeWithText("Save changes").performClick()
        assertEquals(90, model.state.value.shortcuts.first().minutes)
        assertNull(model.state.value.shortcuts.first().endTime)

        compose.runOnIdle {
            val restored = AppState(compose.activity.application).state.value
            assertEquals(model.state.value.roomOrder, restored.roomOrder)
            assertEquals(model.state.value.shortcuts, restored.shortcuts)
            model.runShortcut(Shortcut("partial", "Removed room", model.state.value.home!!.key, listOf("removed", "kitchen"), 19.0, 20))
        }
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.shortcutRuns["partial"] != null }
        assertTrue(model.state.value.shortcutRuns.getValue("partial").first().status.startsWith("Failed"))
        assertEquals(19.0, model.state.value.home!!.rooms.first { it.id == "kitchen" }.target!!, .0)
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Dark").performScrollTo().performClick()
        compose.onNode(hasText("Home") and hasClickAction()).performClick()
        screenshot("06-home-dark")
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Light").performScrollTo().performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasContentDescription("Open EvoBuddy website"))
        screenshot("09-settings")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply {
            addDataScheme("https"); addDataAuthority("evobuddy.datatra.sh", null)
        }
        val monitor = instrumentation.addMonitor(filter, android.app.Instrumentation.ActivityResult(0, null), true)
        try {
            compose.onNodeWithContentDescription("Open EvoBuddy website").performClick()
            assertEquals(1, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
