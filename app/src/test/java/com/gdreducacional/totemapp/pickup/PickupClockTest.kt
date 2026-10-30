package com.gdreducacional.totemapp.pickup

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PickupClockTest {
    private fun utc(value: String): Long = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse(value)!!.time

    @Test fun civilDateUsesBrasiliaAndMonotonicTime() {
        val server = utc("2026-10-29T02:59:00Z")
        val anchor = PickupTimeAnchor(server, 1000, 7, 1000, server + 86_400_000)
        assertEquals("2026-10-28", PickupClock.current(anchor, 1000, 7)?.civilDate)
        assertEquals("2026-10-29", PickupClock.current(anchor, 62_000, 7)?.civilDate)
        assertNull(PickupClock.current(anchor, 60_500, 7))
    }

    @Test fun expirationRebootAndClockRollbackPreventUse() {
        val server = utc("2026-10-28T12:00:00Z")
        val anchor = PickupTimeAnchor(server, 1000, 7, 500, server + 86_400_000)
        assertNotNull(PickupClock.current(anchor, 86_400_000, 7))
        assertNull(PickupClock.current(anchor, 86_400_501, 7))
        assertNull(PickupClock.current(anchor, 2000, 8))
        assertNull(PickupClock.current(anchor, 999, 7))
    }

    @Test fun dateRulesApplyOnCivilDay() {
        assertTrue(PickupClock.allows("UNTIL_DATE", "2026-10-28", "2026-10-28"))
        assertFalse(PickupClock.allows("UNTIL_DATE", "2026-10-28", "2026-10-29"))
        assertFalse(PickupClock.allows("ON_DATE", "2026-10-28", "2026-10-27"))
        assertTrue(PickupClock.allows("ON_DATE", "2026-10-28", "2026-10-28"))
        assertFalse(PickupClock.allows("UNLIMITED", "2026-10-28", "2026-10-28"))
        assertFalse(PickupClock.allows("UNTIL_DATE", "9999-99-99", "2026-10-28"))
    }
}
