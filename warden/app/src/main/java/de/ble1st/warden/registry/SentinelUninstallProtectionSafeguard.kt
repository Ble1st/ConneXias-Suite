package de.ble1st.warden.registry

import android.content.Context
import de.ble1st.warden.domain.profile.SafeguardIds

/**
 * "Sentinel: eigenständige Kiosk-PIN-App" (2026-08-26), Live-Drill-Folgearbeit — bringt genau das
 * zurück, was [de.ble1st.warden.WardenApplication]s Klassendoc noch als "(weiterhin entfallen)"
 * beschreibt: eine `PackageUninstallProtectionSafeguard`-Entsprechung für eine Geschwister-Suite-
 * APK. Seit Sentinel wieder eine eigene, fremde APK ist (nicht mehr in Wardens eigenem Prozess),
 * ist genau dieser Schutz wieder nötig — dieselbe `DevicePolicyManager.setUninstallBlocked`-API
 * wie [SelfUninstallProtectionSafeguard], nur auf `de.ble1st.warden.sentinel` statt
 * `context.packageName` angewandt.
 *
 * **Bewusst über die normale [SafeguardCatalog.reversible]-Registrierung** statt eines
 * Einmal-Aufrufs an einer festen Stelle: dieselben drei Vorteile wie bei jedem anderen Safeguard
 * — überlebt einen [MasterSwitch]-Revert konsistent mit dem Rest des Katalogs, wird von
 * [RegistryReconciler] nach einem Neustart automatisch nachgezogen (schützt auch eine Sentinel-
 * Installation, die aus einer älteren Warden-Version *vor* diesem Fix stammt, ohne dass Nutzer
 * oder Warden das manuell nachholen müssten), und bleibt trotzdem regulär in
 * [de.ble1st.warden.ui.SafeguardsScreen] sichtbar/umschaltbar statt eines versteckten,
 * undokumentierten Sondermechanismus.
 *
 * **"automatisch", wie vom Nutzer verlangt:** [de.ble1st.warden.appmanagement
 * .SentinelInstallResultReceiver] ruft `registry.apply(ID)` direkt bei erfolgreicher
 * Silent-Installation auf — kein manuelles Umschalten in den Safeguards nötig, damit der Schutz
 * tatsächlich vom ersten Moment an greift, in dem Sentinel überhaupt existiert.
 *
 * **Ergänzt, ersetzt nicht** [de.ble1st.warden.appmanagement.AppManagementController
 * .SUITE_PACKAGE_NAMES] (jetzt mit Sentinels Paketnamen) — dieser Safeguard blockiert nur die
 * Deinstallation (`setUninstallBlocked`, wirkt auch gegen Einstellungen/`pm uninstall`), das
 * "unfreezable" aus derselben Nutzeranforderung läuft strukturell über [AppFreezeGuard]
 * ([AppManagementController]/[de.ble1st.warden.appmanagement.SuspiciousAppScanController] prüfen
 * das bereits *vor* jedem `setFrozen()`/Deinstall-Aufruf, unabhängig von diesem Safeguard hier) —
 * zwei unabhängige Schichten für zwei unabhängige Angriffsflächen, dasselbe Muster wie überall
 * sonst im Projekt.
 */
class SentinelUninstallProtectionSafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        devicePolicyManager().setUninstallBlocked(admin, WardenLockTaskAuthorizer.SENTINEL_PACKAGE_NAME, true)
    }

    override fun revert() {
        devicePolicyManager().setUninstallBlocked(admin, WardenLockTaskAuthorizer.SENTINEL_PACKAGE_NAME, false)
    }

    override fun isActive(): Boolean =
        devicePolicyManager().isUninstallBlocked(admin, WardenLockTaskAuthorizer.SENTINEL_PACKAGE_NAME)

    companion object {
        // analyse.md (2. Durchgang, Hoch): geteilte Konstante mit WardenProfileApplyDecision.
        // NEVER_TOUCHED statt eines eigenen Literals hier — s. dortiges Klassendoc für die
        // Begründung, warum kein Profil-Apply diesen Safeguard je anfassen darf.
        const val ID = SafeguardIds.SENTINEL_UNINSTALL_PROTECTION
    }
}
