package de.ble1st.warden.sentinel.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SentinelLockTaskGate] ist absichtlich trivial — der Test stellt trotzdem strukturell sicher,
 * dass `isLockTaskPermitted` niemals ohne das Bit selbst `true` liefert (kein implizites/
 * gehärtetes "Standard an", s. Klassendoc). */
class SentinelLockTaskGateTest {

    @Test
    fun permittedOnlyWhenDrillPassed() {
        assertTrue(SentinelLockTaskGate.isLockTaskPermitted(emergencyCallDrillPassed = true))
    }

    @Test
    fun deniedWhenDrillNotPassed() {
        assertFalse(SentinelLockTaskGate.isLockTaskPermitted(emergencyCallDrillPassed = false))
    }
}
