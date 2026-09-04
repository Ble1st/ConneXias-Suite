package de.ble1st.warden.domain.appmanagement

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionEscalationDecisionTest {

    @Test
    fun packageWithoutPreviousBaselineIsNeverFlagged() {
        val result = PermissionEscalationDecision.evaluate(
            previousDangerousPermissions = emptyMap(),
            currentDangerousPermissions = mapOf("pkg.a" to setOf("android.permission.CAMERA")),
        )
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun newDangerousPermissionIsFlagged() {
        val result = PermissionEscalationDecision.evaluate(
            previousDangerousPermissions = mapOf("pkg.a" to setOf("android.permission.CAMERA")),
            currentDangerousPermissions = mapOf("pkg.a" to setOf("android.permission.CAMERA", "android.permission.READ_SMS")),
        )
        assertEquals(setOf("pkg.a"), result)
    }

    @Test
    fun unchangedPermissionSetIsNotFlagged() {
        val result = PermissionEscalationDecision.evaluate(
            previousDangerousPermissions = mapOf("pkg.a" to setOf("android.permission.CAMERA")),
            currentDangerousPermissions = mapOf("pkg.a" to setOf("android.permission.CAMERA")),
        )
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun removedPermissionIsNotFlagged() {
        val result = PermissionEscalationDecision.evaluate(
            previousDangerousPermissions = mapOf("pkg.a" to setOf("android.permission.CAMERA", "android.permission.READ_SMS")),
            currentDangerousPermissions = mapOf("pkg.a" to setOf("android.permission.CAMERA")),
        )
        assertEquals(emptySet<String>(), result)
    }
}
