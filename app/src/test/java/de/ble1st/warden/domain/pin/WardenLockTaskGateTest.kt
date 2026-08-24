package de.ble1st.warden.domain.pin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Kein WardenWatchdogDecisionTest-Äquivalent mehr (Cross-Process-linkToDeath()-Todeseskalation
// entfällt, s. WardenLockTaskGate-Klassendoc) — nur noch das Notruf-Drill-Gate selbst zu testen,
// explizit mit beiden Werten von emergencyCallDrillPassed (Plan-Abschnitt "Verifikation").
class WardenLockTaskGateTest {

    @Test
    fun forbidsLockTaskWithoutPassedDrill() {
        assertFalse(WardenLockTaskGate.isLockTaskPermitted(emergencyCallDrillPassed = false))
    }

    @Test
    fun permitsLockTaskOnlyAfterPassedDrill() {
        assertTrue(WardenLockTaskGate.isLockTaskPermitted(emergencyCallDrillPassed = true))
    }
}
