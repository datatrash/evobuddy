package sh.datatra.evobuddy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RoomControlsTest {
    private val now = Instant.parse("2026-11-13T14:30:00Z") // 15:30 in Amsterdam
    private val schedule = WeekSchedule(List(7) { listOf(Switchpoint(390, 20.0), Switchpoint(960, 18.0), Switchpoint(1080, 21.0)) })
    private val room = Room("living", "Living room", 20.1, 22.0, "TemporaryOverride", now.plusSeconds(3600), schedule = schedule)
    private val home = demoSnapshot().homes.first().copy(rooms = listOf(room))
    @Test fun `override expiry takes precedence over intermediate schedule changes`() {
        val event = nextRoomEvent(room, home, now)!!
        assertEquals(now.plusSeconds(3600), event.at)
        assertEquals(18.0, event.temperature!!, .0)
        assertTrue(event.resumesSchedule)
    }
    @Test fun `expiry before next switchpoint restores current scheduled temperature`() {
        val event = nextRoomEvent(room.copy(until = now.plusSeconds(600)), home, now)!!
        assertEquals(20.0, event.temperature!!, .0)
    }
    @Test fun `expiry exactly on switchpoint uses the new setpoint`() {
        val event = nextRoomEvent(room.copy(until = Instant.parse("2026-11-13T15:00:00Z")), home, now)!!
        assertEquals(18.0, event.temperature!!, .0)
    }
    @Test fun `expired override is treated as following schedule`() {
        val event = nextRoomEvent(room.copy(until = now.minusSeconds(60)), home, now)!!
        assertEquals(Instant.parse("2026-11-13T15:00:00Z"), event.at)
        assertFalse(event.resumesSchedule)
    }
    @Test fun `permanent hold has no implied next schedule change`() { assertNull(nextRoomEvent(room.copy(mode = "PermanentOverride", until = null), home, now)) }
    @Test fun `missing schedule still shows the known override expiry without guessing temperature`() {
        val event = nextRoomEvent(room.copy(schedule = null), home, now)!!
        assertEquals(room.until, event.at); assertNull(event.temperature)
    }
    @Test fun `active eco and mode expiry are reflected in resumption`() {
        assertEquals(15.0, nextRoomEvent(room, home.copy(mode = "AutoWithEco"), now)!!.temperature!!, .0)
        assertEquals(18.0, nextRoomEvent(room, home.copy(mode = "AutoWithEco", modeUntil = now.plusSeconds(600)), now)!!.temperature!!, .0)
        assertNull(nextRoomEvent(room, home.copy(mode = "Away"), now)!!.temperature)
    }
    @Test fun `legacy single room shortcuts migrate without losing identity or settings`() {
        val json = JSONObject("""{"id":"old","name":"Office","home":"house","room":"room-1","temperature":20.5,"minutes":60}""")
        val shortcut = Shortcut.parse(json)
        assertEquals(listOf("room-1"), shortcut.roomIds)
        assertEquals("old", shortcut.id)
        assertEquals(20.5, shortcut.temperature, .0)
        assertEquals(shortcut, Shortcut.parse(shortcut.json()))
    }
    @Test fun `multi room shortcuts retain order and remove duplicate selections`() {
        val shortcut = Shortcut("new", "Evening", "house", listOf("one", "two"), 20.0, 60)
        assertEquals(shortcut, Shortcut.parse(shortcut.json()))
        assertThrows(IllegalArgumentException::class.java) { Shortcut.parse(shortcut.json().put("rooms", org.json.JSONArray())) }
    }
    @Test fun `shared temperature choices respect every selected controller`() {
        val narrow = room.copy(id = "two", min = 18.0, max = 22.0, step = 1.0)
        assertEquals(listOf(18.0, 19.0, 20.0, 21.0, 22.0), sharedTemperatures(listOf(room, narrow)))
        assertTrue(sharedTemperatures(listOf(room.copy(min = 5.0, max = 10.0), narrow)).isEmpty())
        assertTrue(sharedTemperatures(emptyList()).isEmpty())
    }
    @Test fun `dragging changes the insertion order and ignores stale room IDs`() {
        assertEquals(listOf("b", "c", "a"), moveRoomOrder(listOf("a", "b", "c"), "a", "c"))
        assertEquals(listOf("a", "b", "c"), moveRoomOrder(listOf("a", "b", "c"), "missing", "c"))
    }
    @Test fun `manual room order wins over favourites and new rooms are appended`() {
        val rooms = listOf(room, room.copy(id = "kitchen"), room.copy(id = "new"))
        val ordered = orderedRooms(home.copy(rooms = rooms), listOf("removed", "kitchen", "living"), setOf("${home.key}:living"))
        assertEquals(listOf("kitchen", "living", "new"), ordered.map { it.id })
    }
}
