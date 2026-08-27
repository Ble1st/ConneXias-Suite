package de.ble1st.warden.admin

import android.app.admin.ConnectEvent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.app.admin.DnsEvent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import de.ble1st.warden.netlock.NetworkEventLogStore
import de.ble1st.warden.netlock.NetworkLogEntry
import de.ble1st.warden.netlock.NetworkLogEventKind
import de.ble1st.warden.wardenAuditLog

/**
 * Einstiegspunkt für die DPM-/Device-Owner-Lebenszyklus-Callbacks (Konzept Abschnitt 3/12).
 *
 * Die einzige Device-Owner-fähige Komponente im gesamten Projekt (Konzept Abschnitt 1) —
 * deklariert in `:core:data`s AndroidManifest.xml, per Manifest-Merge Teil von `:warden-app`.
 *
 * Ursprünglich (Meilenstein A.2) bewusst dünn gehalten — "nacktes Gerüst", App nur baubar/
 * installierbar/aktivierbar, nur Logging, keine Registry-/Reconciliation-Logik. Boot-
 * Reconciliation (`ACTION_LOCKED_BOOT_COMPLETED`, Konzept 4) lebt seit Meilenstein C in
 * [de.ble1st.warden.boot.RegistryReconciliationReceiver], nicht hier — dieser Receiver bleibt
 * bewusst auf reine DPM-Lifecycle-Callbacks beschränkt.
 *
 * **`onSecurityLogsAvailable`/`onNetworkLogsAvailable` (Tier 5 "Forensik/Audit", 2026-08-22):**
 * das OS ruft diese Callbacks selbst auf, sobald ein Log-Batch abholbereit ist (kein eigenes
 * Polling nötig) — nur, wenn [de.ble1st.warden.registry.SecurityLoggingSafeguard]/
 * [de.ble1st.warden.registry.NetworkLoggingSafeguard] zuvor aktiviert wurden. Beide sind inzwischen
 * über [wardenAuditLog] an den Prozess-weiten [de.ble1st.warden.logging.HashChainLogStore]
 * verkabelt (anders als der A.2-Stand oben) — der Audit-Log bekommt weiterhin nur die
 * Ereigniszahl pro Batch, nicht jedes `SecurityEvent`-Feld (würde den Hash-Chain-Log aufblähen).
 *
 * **"Netz-Sperre" (2026-08-27) — `onNetworkLogsAvailable` parst zusätzlich strukturiert:**
 * jedes `DnsEvent`/`ConnectEvent` aus dem Batch landet vollständig geparst in
 * [de.ble1st.warden.netlock.NetworkEventLogStore] (eigener, von der Audit-Hash-Kette getrennter
 * Ringpuffer — s. dortiges Klassendoc für die Begründung der Trennung). `onSecurityLogsAvailable`
 * bleibt bewusst bei der reinen Ereigniszahl — für `SecurityEvent` gibt es (noch) keine eigene
 * Auswertungs-UI, die die strukturierten Felder bräuchte.
 */
class WardenDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin aktiviert (onEnabled)")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(TAG, "Device Admin deaktiviert (onDisabled) — sollte für Warden als Device Owner nicht regulär vorkommen")
        super.onDisabled(context, intent)
    }

    override fun onSecurityLogsAvailable(context: Context, intent: Intent) {
        super.onSecurityLogsAvailable(context, intent)
        try {
            val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)
            val dpm = checkNotNull(context.getSystemService(DevicePolicyManager::class.java))
            val events = dpm.retrieveSecurityLogs(admin)
            wardenAuditLog(context).append(
                Log.INFO,
                TAG,
                "Sicherheits-Log-Batch verfügbar: ${events?.size ?: 0} Ereignisse",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sicherheits-Log-Abruf fehlgeschlagen", e)
        }
    }

    override fun onNetworkLogsAvailable(context: Context, intent: Intent, batchToken: Long, networkLogsCount: Int) {
        super.onNetworkLogsAvailable(context, intent, batchToken, networkLogsCount)
        try {
            val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)
            val dpm = checkNotNull(context.getSystemService(DevicePolicyManager::class.java))
            val events = dpm.retrieveNetworkLogs(admin, batchToken)
            wardenAuditLog(context).append(
                Log.INFO,
                TAG,
                "Netzwerk-Log-Batch verfügbar: ${events?.size ?: networkLogsCount} Ereignisse (token=$batchToken)",
            )
            if (!events.isNullOrEmpty()) {
                val parsed = events.mapNotNull(::parseNetworkEvent)
                NetworkEventLogStore(NetworkEventLogStore.buildEnvelopeFile(context)).append(parsed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Netzwerk-Log-Abruf fehlgeschlagen", e)
        }
    }

    /** `null` für jeden Event-Typ jenseits `DnsEvent`/`ConnectEvent` (die DPM-API deklariert
     * `NetworkEvent` offen für künftige Erweiterung) — fail-open auf der Parse-Ebene, derselbe
     * Grundsatz wie `dns_filter::parse_query_name` (Rust-Seite): ein nicht erkannter Event-Typ
     * wird stillschweigend übersprungen statt den ganzen Batch abzubrechen. */
    private fun parseNetworkEvent(event: android.app.admin.NetworkEvent): NetworkLogEntry? = when (event) {
        is DnsEvent -> NetworkLogEntry(
            timestampMillis = event.timestamp,
            packageName = event.packageName,
            kind = NetworkLogEventKind.DNS,
            detail = event.hostname ?: "?",
        )
        is ConnectEvent -> NetworkLogEntry(
            timestampMillis = event.timestamp,
            packageName = event.packageName,
            kind = NetworkLogEventKind.CONNECT,
            detail = "${event.inetAddress?.hostAddress ?: "?"}:${event.port}",
        )
        else -> null
    }

    private companion object {
        const val TAG = "WardenDeviceAdmin"
    }
}
