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
