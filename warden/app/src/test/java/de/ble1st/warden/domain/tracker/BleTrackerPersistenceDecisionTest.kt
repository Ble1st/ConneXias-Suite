package de.ble1st.warden.domain.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleTrackerPersistenceDecisionTest {

    @Test
    fun notSuspiciousBelowSightingThreshold() {
        assertFalse(
            BleTrackerPersistenceDecision.isSuspicious(
                sightingCount = 2, firstSeenMillis = 0, lastSeenMillis = 10_000, minSightings = 3, minSpanMillis = 5_000,
            ),
        )
    }

    @Test
    fun notSuspiciousBelowSpanThreshold() {
        assertFalse(
            BleTrackerPersistenceDecision.isSuspicious(
                sightingCount = 5, firstSeenMillis = 0, lastSeenMillis = 1_000, minSightings = 3, minSpanMillis = 5_000,
            ),
        )
    }

    @Test
    fun suspiciousWhenBothThresholdsMet() {
        assertTrue(
            BleTrackerPersistenceDecision.isSuspicious(
                sightingCount = 3, firstSeenMillis = 0, lastSeenMillis = 5_000, minSightings = 3, minSpanMillis = 5_000,
            ),
        )
    }
}
