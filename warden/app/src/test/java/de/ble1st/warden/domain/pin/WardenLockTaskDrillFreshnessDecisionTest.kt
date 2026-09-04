package de.ble1st.warden.domain.pin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WardenLockTaskDrillFreshnessDecisionTest {

    @Test
    fun neverConfirmedIsNotFresh() {
        assertFalse(
            WardenLockTaskDrillFreshnessDecision.isFresh(
                confirmedAtMillis = null,
                nowMillis = 1_000_000L,
            ),
        )
    }

    @Test
    fun exactlyAtMaxAgeIsStillFresh() {
        val maxAge = WardenLockTaskDrillFreshnessDecision.DEFAULT_MAX_AGE_MILLIS
        assertTrue(
            WardenLockTaskDrillFreshnessDecision.isFresh(
                confirmedAtMillis = 0L,
                nowMillis = maxAge,
                maxAgeMillis = maxAge,
            ),
        )
    }

    @Test
    fun oneTickPastMaxAgeIsNotFresh() {
        val maxAge = WardenLockTaskDrillFreshnessDecision.DEFAULT_MAX_AGE_MILLIS
        assertFalse(
            WardenLockTaskDrillFreshnessDecision.isFresh(
                confirmedAtMillis = 0L,
                nowMillis = maxAge + 1,
                maxAgeMillis = maxAge,
            ),
        )
    }

    @Test
    fun futureConfirmationTimestampCountsAsFresh() {
        assertTrue(
            WardenLockTaskDrillFreshnessDecision.isFresh(
                confirmedAtMillis = 1_000_000L,
                nowMillis = 0L,
            ),
        )
    }
}
