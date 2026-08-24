package de.ble1st.warden.registry

import android.content.Context
import de.ble1st.warden.domain.registry.Safeguard

/**
 * Single registration list for every [PersistentSafeguardRegistry] on the shared envelope file.
 * Concord / boot reconciliation use [registerReversible] (no lockdown — that stays presence-gated
 * and is not auto-rearmed after reboot). Failsafe and [SensitiveAction] master-switch use
 * [registerAll] so disarm actually covers lockdown plus every reversible toggle.
 *
 * Adding a safeguard here is what makes it survive a Failsafe / MasterSwitch snapshot write;
 * callers must not assemble their own subset.
 */
object SafeguardCatalog {

    fun reversible(context: Context): List<Safeguard> = listOf(
        CameraSafeguard(context),
        ScreenCaptureSafeguard(context),
        UserRestrictionSafeguard.installUnknownSourcesDisabled(context),
        UserRestrictionSafeguard.configDateTimeDisabled(context),
        SelfUninstallProtectionSafeguard(context),
        ForceStopProtectionSafeguard(context),
        KeyguardHardeningSafeguard(context),
        AccessibilityLockdownSafeguard(context),
        InputMethodLockdownSafeguard(context),
        SecurityLoggingSafeguard(context),
        NetworkLoggingSafeguard(context),
        PasswordComplexitySafeguard(context),
        AutoLockTimeoutSafeguard(context),
        BackupServiceLockdownSafeguard(context),
        SystemUpdatePolicySafeguard(context),
        LockScreenPrivacySafeguard(context),
        UserRestrictionSafeguard.microphoneMuted(context),
        UserRestrictionSafeguard.credentialConfigDisabled(context),
        UserRestrictionSafeguard.physicalMediaMountDisabled(context),
        // Everyday reset-path hardening (also members of DeviceLockdownBundle).
        UserRestrictionSafeguard.factoryResetDisabled(context),
        UserRestrictionSafeguard.safeBootDisabled(context),
        UserRestrictionSafeguard.modifyAccountsDisabled(context),
        FactoryResetProtectionSafeguard(context),
        // Permanent USB signaling off — independent of UsbAutoLockController (screen-lock poll).
        UsbDataSignalingSafeguard(context),
    )

    fun registerReversible(registry: PersistentSafeguardRegistry, context: Context) {
        for (safeguard in reversible(context)) {
            registry.register(safeguard)
        }
    }

    fun registerAll(registry: PersistentSafeguardRegistry, context: Context) {
        registerReversible(registry, context)
        registry.register(DeviceLockdownBundle.build(context))
    }
}
