package de.ble1st.warden.domain.appmanagement

import org.junit.Assert.assertEquals
import org.junit.Test

class SuspiciousSignalTest {

    @Test
    fun emptySetRoundTripsToZero() {
        assertEquals(0, SuspiciousSignal.toBitmask(emptySet()))
        assertEquals(emptySet<SuspiciousSignal>(), SuspiciousSignal.fromBitmask(0))
    }

    @Test
    fun singleSignalRoundTrips() {
        for (signal in SuspiciousSignal.entries) {
            val bitmask = SuspiciousSignal.toBitmask(setOf(signal))
            assertEquals(setOf(signal), SuspiciousSignal.fromBitmask(bitmask))
        }
    }

    @Test
    fun allSignalsRoundTripTogether() {
        val all = SuspiciousSignal.entries.toSet()
        assertEquals(all, SuspiciousSignal.fromBitmask(SuspiciousSignal.toBitmask(all)))
    }

    @Test
    fun distinctSignalsUseDistinctBits() {
        val bits = SuspiciousSignal.entries.map { it.bit }
        assertEquals(bits.size, bits.toSet().size)
    }
}
