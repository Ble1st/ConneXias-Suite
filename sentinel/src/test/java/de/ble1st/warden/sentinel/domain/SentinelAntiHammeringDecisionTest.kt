package de.ble1st.warden.sentinel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1:1-Port-Test von `de.ble1st.warden.domain.pin.WardenAntiHammeringDecisionTest` — s.
 * [SentinelAntiHammeringDecision]-Klassendoc für die Begründung der Duplizierung. */
class SentinelAntiHammeringDecisionTest {

    @Test
    fun noBackoffBelowStartThreshold() {
        for (attempts in 0..4) {
            assertEquals("attempts=$attempts", 0L, SentinelAntiHammeringDecision.backoffSecondsFor(attempts))
        }
    }

    @Test
    fun backoffGrowsExponentiallyFromThreshold() {
        assertEquals(1L, SentinelAntiHammeringDecision.backoffSecondsFor(5))
        assertEquals(2L, SentinelAntiHammeringDecision.backoffSecondsFor(6))
        assertEquals(4L, SentinelAntiHammeringDecision.backoffSecondsFor(7))
        assertEquals(8L, SentinelAntiHammeringDecision.backoffSecondsFor(8))
    }

    @Test
    fun backoffIsCappedAtOneHour() {
        assertEquals(3600L, SentinelAntiHammeringDecision.backoffSecondsFor(20))
        assertEquals(3600L, SentinelAntiHammeringDecision.backoffSecondsFor(10_000))
    }

    @Test
    fun attemptBlockedBeforeBackoffExpires() {
        assertFalse(SentinelAntiHammeringDecision.isAttemptAllowedNow(backoffUntilEpochSeconds = 100, nowEpochSeconds = 99))
    }

    @Test
    fun attemptAllowedExactlyAtBackoffExpiry() {
        assertTrue(SentinelAntiHammeringDecision.isAttemptAllowedNow(backoffUntilEpochSeconds = 100, nowEpochSeconds = 100))
    }

    @Test
    fun attemptAllowedWhenNoBackoffActive() {
        assertTrue(SentinelAntiHammeringDecision.isAttemptAllowedNow(backoffUntilEpochSeconds = 0, nowEpochSeconds = 0))
    }
}
