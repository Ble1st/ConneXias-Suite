package de.ble1st.warden.domain.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbAutoLockDecisionTest {

    @Test
    fun lockedAlwaysDisables() {
        assertEquals(
            UsbAutoLockDecision.Action.Disable,
            UsbAutoLockDecision.action(
                isLocked = true,
                registryLoadFailed = false,
                permanentUsbOffDesired = false,
                lockdownDesired = false,
            ),
        )
    }

    @Test
    fun unlockedReenablesWhenNothingElseWantsUsbOff() {
        assertEquals(
            UsbAutoLockDecision.Action.Enable,
            UsbAutoLockDecision.action(
                isLocked = false,
                registryLoadFailed = false,
                permanentUsbOffDesired = false,
                lockdownDesired = false,
            ),
        )
    }

    @Test
    fun unlockedDoesNotReenableWhenPermanentUsbOffIsDesired() {
        assertEquals(
            UsbAutoLockDecision.Action.Disable,
            UsbAutoLockDecision.action(
                isLocked = false,
                registryLoadFailed = false,
                permanentUsbOffDesired = true,
                lockdownDesired = false,
            ),
        )
    }

    @Test
    fun unlockedDoesNotReenableWhenLockdownIsDesired() {
        assertEquals(
            UsbAutoLockDecision.Action.Disable,
            UsbAutoLockDecision.action(
                isLocked = false,
                registryLoadFailed = false,
                permanentUsbOffDesired = false,
                lockdownDesired = true,
            ),
        )
    }

    @Test
    fun unlockedLeavesUsbAloneWhenRegistryCannotBeRead() {
        assertEquals(
            UsbAutoLockDecision.Action.LeaveAsIs,
            UsbAutoLockDecision.action(
                isLocked = false,
                registryLoadFailed = true,
                permanentUsbOffDesired = false,
                lockdownDesired = false,
            ),
        )
    }
}
