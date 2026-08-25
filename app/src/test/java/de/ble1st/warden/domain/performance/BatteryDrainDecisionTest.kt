package de.ble1st.warden.domain.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryDrainDecisionTest {

    @Test
    fun fewerThanTwoSamplesIsNull() {
        assertNull(BatteryDrainDecision.percentPerHour(emptyList()))
        assertNull(BatteryDrainDecision.percentPerHour(listOf(0L to 80)))
    }

    @Test
    fun zeroElapsedTimeIsNull() {
        assertNull(BatteryDrainDecision.percentPerHour(listOf(1_000L to 80, 1_000L to 79)))
    }

    @Test
    fun computesPercentPerHour() {
        // 10% over 2 hours -> 5%/h.
        val twoHoursMillis = 2 * 3_600_000L
        val result = BatteryDrainDecision.percentPerHour(listOf(0L to 90, twoHoursMillis to 80))
        assertEquals(5.0, result!!, 0.001)
    }
}
