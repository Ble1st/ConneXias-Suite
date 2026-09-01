package de.ble1st.warden.domain.failedattempts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailedAttemptsRebootDecisionTest {

    @Test
    fun disabledWhenThresholdIsNullOrNotPositive() {
        assertFalse(FailedAttemptsRebootDecision.shouldReboot(threshold = null, failedAttempts = 99))
        assertFalse(FailedAttemptsRebootDecision.shouldReboot(threshold = 0, failedAttempts = 99))
        assertFalse(FailedAttemptsRebootDecision.shouldReboot(threshold = -1, failedAttempts = 99))
    }

    @Test
    fun doesNotRebootBelowThreshold() {
        assertFalse(FailedAttemptsRebootDecision.shouldReboot(threshold = 5, failedAttempts = 4))
    }

    @Test
    fun rebootsExactlyAtThreshold() {
        assertTrue(FailedAttemptsRebootDecision.shouldReboot(threshold = 5, failedAttempts = 5))
    }

    /** Ein verpasster Reset (Prozess gekillt zwischen zwei Fehlversuchen) darf nicht dazu führen,
     * dass der Zähler über die Schwelle hinausläuft und nie mehr auslöst. */
    @Test
    fun rebootsAboveThreshold() {
        assertTrue(FailedAttemptsRebootDecision.shouldReboot(threshold = 5, failedAttempts = 7))
    }

    @Test
    fun neverRebootsWithoutAnyFailedAttempt() {
        assertFalse(FailedAttemptsRebootDecision.shouldReboot(threshold = 3, failedAttempts = 0))
    }
}
