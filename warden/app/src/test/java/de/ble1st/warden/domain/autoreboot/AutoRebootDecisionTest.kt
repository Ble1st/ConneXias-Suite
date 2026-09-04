package de.ble1st.warden.domain.autoreboot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRebootDecisionTest {

    private val oneHourMillis = 60 * 60 * 1000L

    @Test
    fun disabledWhenThresholdIsNull() {
        assertFalse(
            AutoRebootDecision.shouldReboot(
                isLockedNow = true,
                lastSeenUnlockedMillis = 0L,
                nowMillis = 10 * oneHourMillis,
                thresholdMillis = null,
            ),
        )
    }

    @Test
    fun disabledWhenThresholdIsZeroOrNegative() {
        assertFalse(
            AutoRebootDecision.shouldReboot(
                isLockedNow = true,
                lastSeenUnlockedMillis = 0L,
                nowMillis = 10 * oneHourMillis,
                thresholdMillis = 0L,
            ),
        )
        assertFalse(
            AutoRebootDecision.shouldReboot(
                isLockedNow = true,
                lastSeenUnlockedMillis = 0L,
                nowMillis = 10 * oneHourMillis,
                thresholdMillis = -1L,
            ),
        )
    }

    @Test
    fun noRebootWhileCurrentlyUnlocked() {
        assertFalse(
            AutoRebootDecision.shouldReboot(
                isLockedNow = false,
                lastSeenUnlockedMillis = 0L,
                nowMillis = 10 * oneHourMillis,
                thresholdMillis = oneHourMillis,
            ),
        )
    }

    @Test
    fun noRebootWhenBaselineUnknown() {
        assertFalse(
            AutoRebootDecision.shouldReboot(
                isLockedNow = true,
                lastSeenUnlockedMillis = null,
                nowMillis = 10 * oneHourMillis,
                thresholdMillis = oneHourMillis,
            ),
        )
    }

    @Test
    fun noRebootWithinWindow() {
        assertFalse(
            AutoRebootDecision.shouldReboot(
                isLockedNow = true,
                lastSeenUnlockedMillis = 0L,
                nowMillis = oneHourMillis - 1,
                thresholdMillis = oneHourMillis,
            ),
        )
    }

    @Test
    fun rebootsExactlyAtThreshold() {
        assertTrue(
            AutoRebootDecision.shouldReboot(
                isLockedNow = true,
                lastSeenUnlockedMillis = 0L,
                nowMillis = oneHourMillis,
                thresholdMillis = oneHourMillis,
            ),
        )
    }

    @Test
    fun rebootsWellPastThreshold() {
        assertTrue(
            AutoRebootDecision.shouldReboot(
                isLockedNow = true,
                lastSeenUnlockedMillis = 0L,
                nowMillis = 10 * oneHourMillis,
                thresholdMillis = oneHourMillis,
            ),
        )
    }
}
