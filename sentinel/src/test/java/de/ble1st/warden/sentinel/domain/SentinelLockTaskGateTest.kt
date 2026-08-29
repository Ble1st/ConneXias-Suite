package de.ble1st.warden.sentinel.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SentinelLockTaskGate] ist absichtlich trivial — der Test stellt trotzdem strukturell sicher,
 * dass `isLockTaskPermitted` niemals ohne beide Bits `true` liefert (kein implizites/gehärtetes
 * "Standard an", s. Klassendoc). Die `pinConfigured`-Fälle decken den 2026-08-28 gefundenen
 * Zustand ab, in dem der Kiosk ohne eingerichtete Sentinel-PIN startete und dort die
 * Ersteinrichtung anbot — also seinen eigenen Ausstieg. */
class SentinelLockTaskGateTest {

    @Test
    fun permittedOnlyWhenDrillPassedAndPinConfigured() {
        assertTrue(
            SentinelLockTaskGate.isLockTaskPermitted(
                emergencyCallDrillPassed = true,
                pinConfigured = true,
            ),
        )
    }

    @Test
    fun deniedWhenDrillNotPassed() {
        assertFalse(
            SentinelLockTaskGate.isLockTaskPermitted(
                emergencyCallDrillPassed = false,
                pinConfigured = true,
            ),
        )
    }

    @Test
    fun deniedWhenPinNotConfigured() {
        assertFalse(
            SentinelLockTaskGate.isLockTaskPermitted(
                emergencyCallDrillPassed = true,
                pinConfigured = false,
            ),
        )
    }

    @Test
    fun deniedWhenNeitherConditionHolds() {
        assertFalse(
            SentinelLockTaskGate.isLockTaskPermitted(
                emergencyCallDrillPassed = false,
                pinConfigured = false,
            ),
        )
    }
}
