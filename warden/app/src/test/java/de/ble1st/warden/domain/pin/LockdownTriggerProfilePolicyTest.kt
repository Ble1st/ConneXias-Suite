package de.ble1st.warden.domain.pin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockdownTriggerProfilePolicyTest {

    @Test
    fun quickTriggerEntryPointsDisabledOnlyForStrict() {
        assertFalse(LockdownTriggerProfilePolicy.quickTriggerEntryPointsEnabled(LockdownTriggerProfile.STRICT))
        assertTrue(LockdownTriggerProfilePolicy.quickTriggerEntryPointsEnabled(LockdownTriggerProfile.STANDARD))
        assertTrue(LockdownTriggerProfilePolicy.quickTriggerEntryPointsEnabled(LockdownTriggerProfile.FAST))
    }

    @Test
    fun confirmationDialogRequiredOnlyForStandard() {
        assertFalse(LockdownTriggerProfilePolicy.requiresConfirmationDialog(LockdownTriggerProfile.STRICT))
        assertTrue(LockdownTriggerProfilePolicy.requiresConfirmationDialog(LockdownTriggerProfile.STANDARD))
        assertFalse(LockdownTriggerProfilePolicy.requiresConfirmationDialog(LockdownTriggerProfile.FAST))
    }

    @Test
    fun sessionPresenceReuseDisabledOnlyForStrict() {
        assertFalse(LockdownTriggerProfilePolicy.allowSessionPresenceReuse(LockdownTriggerProfile.STRICT))
        assertTrue(LockdownTriggerProfilePolicy.allowSessionPresenceReuse(LockdownTriggerProfile.STANDARD))
        assertTrue(LockdownTriggerProfilePolicy.allowSessionPresenceReuse(LockdownTriggerProfile.FAST))
    }
}
