package de.ble1st.warden.registry

import android.content.Context

/**
 * Tier 5 ("Forensik/Audit", 2026-08-22), Pendant zu [SecurityLoggingSafeguard]: Schalter für
 * Androids Netzwerk-Metadaten-Log (`setNetworkLoggingEnabled`/`isNetworkLoggingEnabled`) —
 * DNS-Auflösungen und Verbindungsversuche pro App, kein Klartext-Inhalt. Noch invasiver als das
 * Sicherheitslog, eher ein Enterprise-MDM-Feature als klassischer Diebstahlschutz für ein
 * Einzelgerät — bewusst als eigener, separat abschaltbarer Schalter statt an
 * [SecurityLoggingSafeguard] gekoppelt, damit die Nutzerin beide Privatsphäre-Tradeoffs getrennt
 * abwägen kann. Abholung analog über
 * [de.ble1st.warden.admin.WardenDeviceAdminReceiver.onNetworkLogsAvailable].
 */
class NetworkLoggingSafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        devicePolicyManager().setNetworkLoggingEnabled(admin, true)
    }

    override fun revert() {
        devicePolicyManager().setNetworkLoggingEnabled(admin, false)
    }

    override fun isActive(): Boolean = devicePolicyManager().isNetworkLoggingEnabled(admin)

    companion object {
        const val ID = "network_logging_enabled"
    }
}
