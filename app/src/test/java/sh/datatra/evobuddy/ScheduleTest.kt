package sh.datatra.evobuddy

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScheduleTest {
    private val room = Room("1", "Living room")
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val schedule = WeekSchedule(List(7) { listOf(Switchpoint(390, 20.5), Switchpoint(1320, 16.0)) })
    @Test fun `weekly schedule round trips vendor format`() {
        schedule.validate(room)
        assertEquals(schedule, WeekSchedule.parse(schedule.json()))
        assertEquals("Monday", schedule.json().getJSONArray("dailySchedules").getJSONObject(0).getString("dayOfWeek"))
    }
    @Test fun `duplicate times cannot be sent`() {
        assertThrows(IllegalArgumentException::class.java) { schedule.copy(days = List(7) { listOf(Switchpoint(390, 20.0), Switchpoint(390, 21.0)) }).validate(room) }
    }
    @Test fun `controller limits and precision are enforced`() {
        listOf(Switchpoint(1440, 20.0), Switchpoint(395, 20.0), Switchpoint(390, 40.0), Switchpoint(390, 20.3)).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { WeekSchedule(List(7) { listOf(bad) }).validate(room) }
        }
    }
    @Test fun `empty and incomplete schedules are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { WeekSchedule(emptyList()).validate(room) }
        assertThrows(IllegalArgumentException::class.java) { WeekSchedule(List(7) { emptyList() }).validate(room) }
        val json = schedule.json()
        json.getJSONArray("dailySchedules").remove(0)
        assertThrows(IllegalArgumentException::class.java) { WeekSchedule.parse(json) }
    }
    @Test fun `next switchpoint respects controller zone at week boundary`() {
        val now = Instant.parse("2026-11-15T23:30:00Z") // Monday 00:30 in Amsterdam
        assertEquals(Instant.parse("2026-11-16T05:30:00Z"), schedule.next(now, zone)!!.first)
        assertEquals(16.0, schedule.target(now, zone)!!, 0.0)
    }
    @Test fun `summer time switches use local calendar rather than 24 hour durations`() {
        val next = schedule.next(Instant.parse("2026-03-28T23:00:00Z"), zone)!!
        assertEquals(Instant.parse("2026-03-29T04:30:00Z"), next.first)
    }
    @Test fun `exact switchpoint returns following change`() {
        assertEquals(Instant.parse("2026-11-13T21:00:00Z"), schedule.next(Instant.parse("2026-11-13T05:30:00Z"), zone)!!.first)
    }
    @Test fun `windows zone spelling variants resolve consistently`() {
        assertEquals("Europe/Amsterdam", resolveZone("W. Europe Standard Time"))
        assertEquals("Europe/Amsterdam", resolveZone("WEuropeStandardTime"))
        assertEquals("Europe/London", resolveZone("GMTStandardTime"))
        assertThrows(IllegalArgumentException::class.java) { resolveZone("Unknown zone") }
    }
    @Test fun `snapshot cache preserves schedules and capabilities`() {
        val snapshot = demoSnapshot()
        assertEquals(snapshot, snapshotFromJson(snapshot.json()))
    }
    @Test fun `vendor day order does not change interpretation`() {
        val json = schedule.json()
        val days = json.getJSONArray("dailySchedules")
        json.put("dailySchedules", org.json.JSONArray((0..6).reversed().map { days.getJSONObject(it) }))
        assertEquals(schedule, WeekSchedule.parse(json))
    }
    @Test fun `restored cache is never labelled live before a successful sync`() {
        val snapshot = demoSnapshot()
        assertFalse(hasFreshReadings(UiState(snapshot = snapshot), snapshot.updated))
        assertTrue(hasFreshReadings(UiState(snapshot = snapshot, live = true), snapshot.updated.plusSeconds(60)))
        assertFalse(hasFreshReadings(UiState(snapshot = snapshot, live = true), snapshot.updated.plusSeconds(181)))
        assertFalse(hasFreshReadings(UiState(snapshot = snapshot, live = false, refreshing = true), snapshot.updated.plusSeconds(1)))
    }
}
