package sh.datatra.evobuddy

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CommandPolicyTest {
    private val now = Instant.parse("2026-11-13T14:30:00Z")
    private val command = RoomCommand("id", "home", "living", 20.0, now.plusSeconds(3600), false, now.plusSeconds(3600), now, "shortcut")
    @Test fun `queue round trip preserves absolute end time and retry history`() {
        val retry = command.copy(next = now.plusSeconds(30), attempts = 2, lastWrite = now)
        assertEquals(retry, RoomCommand.parse(retry.json()))
    }
    @Test fun `readback must match mode temperature and expiry`() {
        val actual = Room("living", "Living", target = 20.0, mode = "TemporaryOverride", until = command.until)
        assertTrue(command.matches(actual))
        assertFalse(command.matches(actual.copy(target = 21.0)))
        assertFalse(command.matches(actual.copy(mode = "FollowSchedule")))
        assertFalse(command.matches(actual.copy(until = now.plusSeconds(7200))))
        assertTrue(command.copy(resume = true, temperature = null, until = null).matches(actual.copy(mode = "FollowSchedule", until = null)))
    }
    @Test fun `retry backoff is bounded and respects rate limits`() {
        assertEquals(3L, commandRetrySeconds(0))
        assertEquals(300L, commandRetrySeconds(100))
        assertEquals(60L, commandRetrySeconds(0, true))
    }
    @Test fun `delivery window is five minutes and never extends an earlier expiry`() {
        assertEquals(now.plusSeconds(300), commandDeadline(now, null))
        assertEquals(now.plusSeconds(300), commandDeadline(now, now.plusSeconds(3600)))
        assertEquals(now.plusSeconds(30), commandDeadline(now, now.plusSeconds(30)))
    }
}
