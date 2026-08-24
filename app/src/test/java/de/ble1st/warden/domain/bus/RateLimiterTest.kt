package de.ble1st.warden.domain.bus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Meilenstein E.8 — reiner JVM-Unit-Test dank injizierbarer Uhr, keine Sleep-Flakiness. */
class RateLimiterTest {

    @Test
    fun allowsCallsUpToTheLimitWithinTheWindow() {
        var now = 0L
        val limiter = RateLimiter(maxCallsPerWindow = 3, windowMillis = 1000, now = { now })

        assertTrue(limiter.allow("herald"))
        assertTrue(limiter.allow("herald"))
        assertTrue(limiter.allow("herald"))
    }

    @Test
    fun rejectsCallsBeyondTheLimitWithinTheWindow() {
        var now = 0L
        val limiter = RateLimiter(maxCallsPerWindow = 2, windowMillis = 1000, now = { now })
        limiter.allow("herald")
        limiter.allow("herald")

        assertFalse(limiter.allow("herald"))
    }

    @Test
    fun allowsAgainAfterTheWindowSlidesPast() {
        var now = 0L
        val limiter = RateLimiter(maxCallsPerWindow = 1, windowMillis = 1000, now = { now })
        assertTrue(limiter.allow("herald"))
        assertFalse(limiter.allow("herald"))

        now = 1001L

        assertTrue(limiter.allow("herald"))
    }

    @Test
    fun differentKeysHaveIndependentBudgets() {
        var now = 0L
        val limiter = RateLimiter(maxCallsPerWindow = 1, windowMillis = 1000, now = { now })

        assertTrue(limiter.allow("herald"))
        assertTrue("ein lauter Aufrufer darf einen anderen nicht aussperren", limiter.allow("aegis"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveMaxCallsPerWindow() {
        RateLimiter(maxCallsPerWindow = 0, windowMillis = 1000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveWindow() {
        RateLimiter(maxCallsPerWindow = 1, windowMillis = 0)
    }
}
