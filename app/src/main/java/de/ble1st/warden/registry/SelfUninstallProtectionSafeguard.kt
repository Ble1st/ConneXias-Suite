package de.ble1st.warden.registry

import android.content.Context

/**
 * Tier 1 ("Anti-Tamper", 2026-08-22): blockiert die Deinstallation von Warden selbst über
 * `DevicePolicyManager.setUninstallBlocked`/`isUninstallBlocked` — granularer als
 * `UserManager.DISALLOW_UNINSTALL_APPS` (das würde geräteweit *jede* App vor Deinstallation
 * schützen, nicht nur Warden; da es aktuell keine zu schützenden Geschwister-Apps mehr gibt, s.
 * [AppManagementController.SUITE_PACKAGE_NAMES][de.ble1st.warden.appmanagement.AppManagementController.SUITE_PACKAGE_NAMES],
 * wäre die globale Variante hier nur unnötig einschränkend für den Nutzer selbst).
 *
 * **Bewusst kein Rückbau-Risiko wie `DeviceLockdownBundle`:** anders als `DISALLOW_FACTORY_RESET`/
 * `DISALLOW_SAFE_BOOT` blockiert dieser Safeguard keinen der bekannten DO-Rückbauwege
 * (`adb shell dpm remove-active-admin`, Werksreset) — nur die reguläre Deinstallation über
 * Einstellungen/Launcher. Deshalb regulär über [de.ble1st.warden.ui.SafeguardsScreen]
 * umschaltbar statt dauerhaft dormant wie das Lockdown-Bündel.
 */
class SelfUninstallProtectionSafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        devicePolicyManager().setUninstallBlocked(admin, context.packageName, true)
    }

    override fun revert() {
        devicePolicyManager().setUninstallBlocked(admin, context.packageName, false)
    }

    override fun isActive(): Boolean =
        devicePolicyManager().isUninstallBlocked(admin, context.packageName)

    companion object {
        const val ID = "self_uninstall_protection"
    }
}
