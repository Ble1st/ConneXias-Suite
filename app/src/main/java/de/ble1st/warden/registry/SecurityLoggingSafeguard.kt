package de.ble1st.warden.registry

import android.content.Context

/**
 * Tier 5 ("Forensik/Audit", 2026-08-22) — Schalter für Androids System-Sicherheitslog
 * (`DevicePolicyManager.setSecurityLoggingEnabled`/`isSecurityLoggingEnabled`), parallel zum
 * eigenen [de.ble1st.warden.logging.HashChainLogStore]. Erfasst u. a. Anmeldeversuche,
 * Admin-Aktivierungen, Keystore-Zugriffe, USB-Ereignisse — deutlich mehr als Wardens eigenes
 * Audit-Log, das nur Wardens eigene Aktionen kennt.
 *
 * **Bewusst niedriger priorisiert/Opt-in, nicht automatisch aktiv:** invasiver als die übrigen
 * Tier-1-3-Schalter (sieht u. a. App-Starts) — Privatsphäre-Tradeoff, deshalb hier nur der
 * Ein/Aus-Schalter; die eigentliche Abholung läuft über
 * [de.ble1st.warden.admin.WardenDeviceAdminReceiver.onSecurityLogsAvailable], ausgelöst vom OS
 * selbst (kein Polling nötig), nur eine Ereigniszahl wird ins eigene Audit-Log übernommen (volles
 * Parsen jedes `SecurityEvent`-Felds bewusst zurückgestellt, s. dortiges Klassendoc).
 */
class SecurityLoggingSafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        devicePolicyManager().setSecurityLoggingEnabled(admin, true)
    }

    override fun revert() {
        devicePolicyManager().setSecurityLoggingEnabled(admin, false)
    }

    override fun isActive(): Boolean = devicePolicyManager().isSecurityLoggingEnabled(admin)

    companion object {
        const val ID = "security_logging_enabled"
    }
}
