package de.ble1st.warden.domain.appmanagement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionAuditDecisionTest {

    @Test
    fun belowThresholdIsNotFlagged() {
        assertFalse(PermissionAuditDecision.tooManyDangerousPermissions(PermissionAuditDecision.THRESHOLD - 1))
    }

    @Test
    fun atThresholdIsFlagged() {
        assertTrue(PermissionAuditDecision.tooManyDangerousPermissions(PermissionAuditDecision.THRESHOLD))
    }

    @Test
    fun aboveThresholdIsFlagged() {
        assertTrue(PermissionAuditDecision.tooManyDangerousPermissions(PermissionAuditDecision.THRESHOLD + 10))
    }

    @Test
    fun zeroIsNotFlagged() {
        assertFalse(PermissionAuditDecision.tooManyDangerousPermissions(0))
    }
}
