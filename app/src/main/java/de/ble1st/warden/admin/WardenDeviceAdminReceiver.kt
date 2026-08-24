package de.ble1st.warden.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import de.ble1st.warden.wardenAuditLog

/**
 * Einstiegspunkt für die DPM-/Device-Owner-Lebenszyklus-Callbacks (Konzept Abschnitt 3/12).
 *
 * Die einzige Device-Owner-fähige Komponente im gesamten Projekt (Konzept Abschnitt 1) —
 * deklariert in `:core:data`s AndroidManifest.xml, per Manifest-Merge Teil von `:warden-app`.
 *
 * Bewusst noch dünn (Meilenstein A.2 — "nacktes Gerüst", App nur baubar/installierbar/
 * aktivierbar): reagiert nur mit Logging, keine Registry-/Reconciliation-Logik. Boot-Reconciliation
 * (`ACTION_LOCKED_BOOT_COMPLETED`, Konzept 4) und die eigentliche DPM-Wrapper-Logik folgen ab
 * Meilenstein C.
 *
 * `android.util.Log` statt Timber/`:core:logging`: `LocalRingTree`/`HashChainLogStore` existieren
 * seit Meilenstein B.5/B.6, sind aber bewusst noch nicht hier verkabelt — das passiert erst mit
 * Meilenstein C zusammen mit der Registry/Reconciliation-Logik, nicht isoliert vorgezogen.
 *
 * **`onSecurityLogsAvailable`/`onNetworkLogsAvailable` (Tier 5 "Forensik/Audit", 2026-08-22):**
 * das OS ruft diese Callbacks selbst auf, sobald ein Log-Batch abholbereit ist (kein eigenes
 * Polling nötig) — nur, wenn [de.ble1st.warden.registry.SecurityLoggingSafeguard]/
 * [de.ble1st.warden.registry.NetworkLoggingSafeguard] zuvor aktiviert wurden. Bewusst nur die
 * Ereigniszahl ins eigene [HashChainLogStore] übernommen, nicht jedes einzelne `SecurityEvent`/
 * `NetworkEvent`-Feld geparst — würde den Umfang dieser bewusst dünnen Klasse sprengen und ist für
 * den "wurde überhaupt etwas geloggt"-Zweck des Audit-Trails nicht nötig; die vollen Rohdaten
 * bleiben über `retrieveSecurityLogs`/`retrieveNetworkLogs` grundsätzlich abrufbar, falls künftig
 * eine eigene Auswertungs-UI dazukommt.
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
        } catch (e: Exception) {
            Log.e(TAG, "Netzwerk-Log-Abruf fehlgeschlagen", e)
        }
    }

    private companion object {
        const val TAG = "WardenDeviceAdmin"
    }
}
