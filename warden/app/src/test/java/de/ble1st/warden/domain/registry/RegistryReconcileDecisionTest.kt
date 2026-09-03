package de.ble1st.warden.domain.registry

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * analyse.md (2. Durchgang, Mittel — "USB-Daten am gesperrten Gerät nach Boot wieder an"):
 * Regressionsschutz gegen den Fund, dass Boot-Reconciliation `usb_data_signaling_disabled` wieder
 * anschaltete, während das Gerät noch gesperrt war — s. [RegistryReconcileDecision]-Klassendoc.
 */
class RegistryReconcileDecisionTest {

    @Test
    fun noDivergenceLeavesUntouched() {
        assertEquals(RegistryReconcileAction.LEAVE_UNTOUCHED, RegistryReconcileDecision.actionFor("x", desired = true, actual = true))
        assertEquals(RegistryReconcileAction.LEAVE_UNTOUCHED, RegistryReconcileDecision.actionFor("x", desired = false, actual = false))
    }

    @Test
    fun strengtheningIsAlwaysAllowedEvenForNeverWeakenIds() {
        assertEquals(
            RegistryReconcileAction.APPLY,
            RegistryReconcileDecision.actionFor("usb_data_signaling_disabled", desired = true, actual = false, neverWeaken = setOf("usb_data_signaling_disabled")),
        )
    }

    @Test
    fun weakeningIsRevertedForOrdinaryIds() {
        assertEquals(RegistryReconcileAction.REVERT, RegistryReconcileDecision.actionFor("camera_disabled", desired = false, actual = true))
    }

    @Test
    fun weakeningIsLeftUntouchedForNeverWeakenIds() {
        assertEquals(
            RegistryReconcileAction.LEAVE_UNTOUCHED,
            RegistryReconcileDecision.actionFor("usb_data_signaling_disabled", desired = false, actual = true, neverWeaken = setOf("usb_data_signaling_disabled")),
        )
    }
}
