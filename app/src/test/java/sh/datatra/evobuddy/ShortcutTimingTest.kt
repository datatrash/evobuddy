package sh.datatra.evobuddy

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ShortcutTimingTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    @Test fun `clock shortcuts use next occurrence in controller timezone`() {
        val now = Instant.parse("2026-11-13T14:30:00Z")
        assertEquals(Instant.parse("2026-11-13T21:00:00Z"), nextClockTime(now, zone, LocalTime.of(22, 0)))
        assertEquals(Instant.parse("2026-11-14T14:30:00Z"), nextClockTime(now, zone, LocalTime.of(15, 30)))
        assertEquals(Instant.parse("2026-11-14T11:00:00Z"), nextClockTime(now, zone, LocalTime.NOON))
    }
    @Test fun `clock shortcuts handle missing and repeated daylight saving hours`() {
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), nextClockTime(Instant.parse("2026-03-28T23:00:00Z"), zone, LocalTime.of(2, 30)))
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), nextClockTime(Instant.parse("2026-10-25T00:45:00Z"), zone, LocalTime.of(2, 30)))
    }
    @Test fun `end time survives storage and duration shortcuts retain original behaviour`() {
        val home = demoSnapshot().homes.first()
        val now = Instant.parse("2026-11-13T14:30:00Z")
        val shortcut = Shortcut("clock", "Evening", home.key, listOf("living", "kitchen"), 20.0, 60, "22:00")
        assertEquals(shortcut, Shortcut.parse(shortcut.json()))
        assertEquals(Instant.parse("2026-11-13T21:00:00Z"), shortcut.expiry(home, now))
        assertEquals(now.plusSeconds(3600), shortcut.copy(endTime = null).expiry(home, now))
    }
    @Test fun `background policy skips recent cache and recovers from clock changes`() {
        val now = Instant.parse("2026-11-13T14:30:00Z")
        assertFalse(needsBackgroundRefresh(now.minusSeconds(899), now))
        assertTrue(needsBackgroundRefresh(now.minusSeconds(900), now))
        assertTrue(needsBackgroundRefresh(now.plusSeconds(60), now))
    }
}
