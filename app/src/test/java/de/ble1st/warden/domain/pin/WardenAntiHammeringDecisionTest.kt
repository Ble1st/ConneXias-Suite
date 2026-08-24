package de.ble1st.warden.domain.pin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WardenAntiHammeringDecisionTest {

    @Test
    fun noBackoffBelowStartThreshold() {
        for (attempts in 0..4) {
            assertEquals("attempts=$attempts", 0L, WardenAntiHammeringDecision.backoffSecondsFor(attempts))
        }
    }

    @Test
    fun backoffGrowsExponentiallyFromThreshold() {
        assertEquals(1L, WardenAntiHammeringDecision.backoffSecondsFor(5))
        assertEquals(2L, WardenAntiHammeringDecision.backoffSecondsFor(6))
        assertEquals(4L, WardenAntiHammeringDecision.backoffSecondsFor(7))
        assertEquals(8L, WardenAntiHammeringDecision.backoffSecondsFor(8))
    }

    @Test
    fun backoffIsCappedAtOneHour() {
        assertEquals(3600L, WardenAntiHammeringDecision.backoffSecondsFor(20))
        assertEquals(3600L, WardenAntiHammeringDecision.backoffSecondsFor(10_000))
    }

    @Test
    fun attemptBlockedBeforeBackoffExpires() {
        assertFalse(WardenAntiHammeringDecision.isAttemptAllowedNow(backoffUntilEpochSeconds = 100, nowEpochSeconds = 99))
    }

    @Test
    fun attemptAllowedExactlyAtBackoffExpiry() {
        assertTrue(WardenAntiHammeringDecision.isAttemptAllowedNow(backoffUntilEpochSeconds = 100, nowEpochSeconds = 100))
    }

    @Test
    fun attemptAllowedWhenNoBackoffActive() {
        assertTrue(WardenAntiHammeringDecision.isAttemptAllowedNow(backoffUntilEpochSeconds = 0, nowEpochSeconds = 0))
    }
}
