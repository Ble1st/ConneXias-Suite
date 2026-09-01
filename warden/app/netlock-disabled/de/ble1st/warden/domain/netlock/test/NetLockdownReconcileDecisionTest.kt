// ⏸ PAUSIERT (2026-08-27): "Netz-Sperre" ist vorübergehend deaktiviert — Live-Test auf dem
// physischen Testgerät fand nach mehreren echten Bugfixes (siehe Commit 7252396 und
// warden-netzsperre-feature-2026-08-27-Memo) einen weiterhin ungeklärten Kernfehler: die
// DNS-Blockliste/NAT-Relay verarbeitet auf einem frisch aufgebauten Tunnel keinen Traffic mehr,
// Ursache unbekannt. Diese Datei liegt deshalb bewusst außerhalb jedes Gradle-Source-Sets
// (app/netlock-disabled/ statt app/src/main/java/) — wird NICHT mitkompiliert, ist nirgendwo
// verkabelt. Zum Reaktivieren: Verzeichnis zurück nach app/src/main/java/... verschieben, alle
// Wiederverkabelungsstellen aus dem Deaktivierungs-Commit rückgängig machen (siehe dessen
// Commit-Message für die vollständige Liste), Kernfehler zuerst klären.

package de.ble1st.warden.domain.netlock

import org.junit.Assert.assertEquals
import org.junit.Test

class NetLockdownReconcileDecisionTest {

    @Test
    fun noPersistedDesiredStateDoesNothing() {
        assertEquals(
            NetLockdownReconcileDecision.Action.NoOp,
            NetLockdownReconcileDecision.action(desired = null, actual = false),
        )
        assertEquals(
            NetLockdownReconcileDecision.Action.NoOp,
            NetLockdownReconcileDecision.action(desired = null, actual = true),
        )
    }

    @Test
    fun desiredArmedButNotActiveArms() {
        assertEquals(
            NetLockdownReconcileDecision.Action.Arm,
            NetLockdownReconcileDecision.action(desired = true, actual = false),
        )
    }

    @Test
    fun desiredDisarmedButActiveDisarms() {
        assertEquals(
            NetLockdownReconcileDecision.Action.Disarm,
            NetLockdownReconcileDecision.action(desired = false, actual = true),
        )
    }

    @Test
    fun desiredArmedAndAlreadyActiveDoesNothing() {
        assertEquals(
            NetLockdownReconcileDecision.Action.NoOp,
            NetLockdownReconcileDecision.action(desired = true, actual = true),
        )
    }

    @Test
    fun desiredDisarmedAndAlreadyInactiveDoesNothing() {
        assertEquals(
            NetLockdownReconcileDecision.Action.NoOp,
            NetLockdownReconcileDecision.action(desired = false, actual = false),
        )
    }
}
