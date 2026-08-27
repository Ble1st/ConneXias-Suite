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
        // "LockMode/Threat-Protection-Ausbau" (2026-08-25, auf Nutzerwunsch "als Schalter unter
        // Safeguards"): weiterhin auch DeviceLockdownBundle-Mitglied, s.
        // UserRestrictionSafeguard.debuggingFeaturesDisabled-Klassendoc für die Risiko-Begründung.
        UserRestrictionSafeguard.debuggingFeaturesDisabled(context),
        FactoryResetProtectionSafeguard(context),
        // Permanent USB signaling off — independent of UsbAutoLockController (screen-lock poll).
        UsbDataSignalingSafeguard(context),
        // "Sentinel: eigenständige Kiosk-PIN-App", Live-Drill-Folgearbeit (2026-08-26): automatisch
        // scharf geschaltet direkt bei erfolgreicher Silent-Installation
        // (SentinelInstallResultReceiver), nicht erst hier — s. SentinelUninstallProtectionSafeguard
        // -Klassendoc. Registrierung hier sorgt für Boot-Reconciliation + MasterSwitch-Abdeckung.
        SentinelUninstallProtectionSafeguard(context),
        // "Netz-Sperre" (2026-08-27): NetLockdownAuthorizer ist bewusst NICHT hier registriert,
        // obwohl es die "beides" (Standalone + DeviceLockdownBundle-Mitglied) genannte
        // Nutzeranforderung zunächst nahelegt — anders als die simplen Boolean-Toggles um es herum
        // braucht dessen *korrektes* apply() eine Lockdown-Allowlist (s. dessen Klassendoc), die
        // der generische, no-arg-basierte RegistryReconciler/PersistentSafeguardRegistry-Pfad nicht
        // liefern kann. Würde man es trotzdem hier registrieren, bekäme "net_lockdown" einen
        // *zweiten*, unabhängigen Soll-Zustand in SafeguardRegistryStore (parallel zu
        // NetLockdownController/NetLockdownStore) — und jeder MasterSwitch/Failsafe-Aufruf, der
        // generisch registry.revert("net_lockdown") aufruft, würde diesen zweiten Soll-Zustand auf
        // "false" persistieren. Ein späteres echtes NetLockdownController.arm() aktualisiert diesen
        // zweiten Soll-Zustand nie — der nächste Boot sähe dann fälschlich "Soll=false, Ist=true"
        // und würde die Netz-Sperre über den generischen RegistryReconciler wieder entschärfen,
        // parallel zu (und im Widerspruch mit) reconcileNetLockdown()s eigener, korrekter
        // Boot-Reconciliation. "Standalone" wird stattdessen wie im ConneXias-Framework-
        // Quellprojekt gelöst: ein eigener Ein/Aus-Schalter in NetworkScreen, der direkt
        // NetLockdownController.arm()/disarm() aufruft, ganz ohne den generischen Safeguard-
        // Registry-Pfad — "DeviceLockdownBundle-Mitglied" bleibt trotzdem erfüllt, s. dortige
        // members-Liste.
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
