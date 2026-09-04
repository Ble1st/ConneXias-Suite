package de.ble1st.warden.registry

import android.app.admin.SystemUpdatePolicy
import android.content.Context

/**
 * Eigene Idee (2026-08-22), dritte Ergänzungsrunde: erzwingt automatische Installation von
 * OS-Sicherheitsupdates, sobald verfügbar (`DevicePolicyManager.setSystemUpdatePolicy(admin, …)`/
 * `getSystemUpdatePolicy()` — Getter **ohne** `ComponentName`, anders als der Setter,
 * `SystemUpdatePolicy.createAutomaticInstallPolicy()`) — verkürzt das
 * Zeitfenster, in dem ein Gerät eine bereits gepatchte Sicherheitslücke ungepatcht mitträgt.
 * Anders als die übrigen Härtungs-Schalter kein Verhalten, das *diesem* Gerät direkt etwas
 * abschaltet, sondern die einzige der bisherigen Ideen, die das Gerät aktiv aktueller hält statt
 * nur Angriffsfläche zu reduzieren.
 *
 * `revert()` setzt die Policy auf `null` zurück — laut Android-API der Ausgangszustand
 * ("manuelle/System-Standard-Update-Erfahrung", kein erzwungenes Verhalten).
 */
class SystemUpdatePolicySafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        devicePolicyManager().setSystemUpdatePolicy(admin, SystemUpdatePolicy.createAutomaticInstallPolicy())
    }

    override fun revert() {
        devicePolicyManager().setSystemUpdatePolicy(admin, null)
    }

    override fun isActive(): Boolean =
        devicePolicyManager().systemUpdatePolicy?.policyType == SystemUpdatePolicy.TYPE_INSTALL_AUTOMATIC

    companion object {
        const val ID = "system_update_policy_automatic"
    }
}
