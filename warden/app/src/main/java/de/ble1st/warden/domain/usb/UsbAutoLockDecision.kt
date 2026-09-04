package de.ble1st.warden.domain.usb

/**
 * USB auto-lock (disable signaling while the keyguard is up) must not undo a *desired*
 * permanent USB-off or the lockdown bundle. Unlocking would otherwise re-enable USB and
 * make [de.ble1st.warden.registry.DeviceLockdownBundle] look inactive while factory-reset
 * / safe-boot blocks stay on.
 *
 * If the registry cannot be read, never re-enable — fail-closed.
 */
object UsbAutoLockDecision {

    sealed class Action {
        data object Disable : Action()
        data object Enable : Action()
        data object LeaveAsIs : Action()
    }

    fun action(
        isLocked: Boolean,
        registryLoadFailed: Boolean,
        permanentUsbOffDesired: Boolean,
        lockdownDesired: Boolean,
    ): Action = when {
        isLocked -> Action.Disable
        permanentUsbOffDesired || lockdownDesired -> Action.Disable
        registryLoadFailed -> Action.LeaveAsIs
        else -> Action.Enable
    }
}
