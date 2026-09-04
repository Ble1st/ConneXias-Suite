package de.ble1st.warden.domain.appmanagement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuspiciousAppNotificationActionDecisionTest {

    @Test
    fun unlockedOpenFindingIsAllowed() {
        assertTrue(SuspiciousAppNotificationActionDecision.allowDestructiveAction(deviceLocked = false, isOpenNotifiedFinding = true))
    }

    @Test
    fun lockedDeviceIsRejectedEvenForOpenFinding() {
        assertFalse(SuspiciousAppNotificationActionDecision.allowDestructiveAction(deviceLocked = true, isOpenNotifiedFinding = true))
    }

    @Test
    fun stalePackageIsRejectedWhenUnlocked() {
        assertFalse(SuspiciousAppNotificationActionDecision.allowDestructiveAction(deviceLocked = false, isOpenNotifiedFinding = false))
    }
}
