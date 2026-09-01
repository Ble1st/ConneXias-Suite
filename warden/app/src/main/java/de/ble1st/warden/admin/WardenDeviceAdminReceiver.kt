package de.ble1st.warden.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import de.ble1st.warden.BuildConfig
import de.ble1st.warden.failedattempts.FailedAttemptsRebootController
import de.ble1st.warden.logging.SecurityEventParser
import de.ble1st.warden.wardenAuditLog
import de.ble1st.warden.wardenSecurityEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
 * **"Netz-Sperre" (2026-08-27) — strukturiertes Parsing pausiert:** `onNetworkLogsAvailable`
 * parste zwischenzeitlich jedes `DnsEvent`/`ConnectEvent` zusätzlich strukturiert in einen eigenen
 * `NetworkEventLogStore`-Ringpuffer; seit demselben Tag wieder entfernt, weil "Netz-Sperre" als
 * Ganzes einen ungeklärten Kernfehler im Live-Test hatte (s. `WardenApplication`-Klassendoc) — der
 * Code liegt geparkt unter `app/netlock-disabled/`. `onNetworkLogsAvailable` protokolliert bis zur
 * Reaktivierung wieder nur die reine Ereigniszahl, wie `onSecurityLogsAvailable`.
 *
 * **`onSecurityLogsAvailable`/`onNetworkLogsAvailable` laufen seit Befund Q-4 (2026-08-29) über
 * `goAsync()` + einen Hintergrund-Coroutine, nicht mehr synchron im `onReceive`-Fenster:** Abrufen,
 * Parsen (bis zu 2000 Records) und der volle AES-GCM-Schreibzyklus in [wardenSecurityEvents] plus
 * der zusätzliche [wardenAuditLog]-Eintrag summierten sich auf eine spürbare Zeitspanne, die vorher
 * komplett im knappen Broadcast-Fenster lief — bei Netzwerk-Logging laut eigener Dokumentation
 * täglich tausendfach. `goAsync()` liefert ein [android.content.BroadcastReceiver.PendingResult],
 * dessen [android.content.BroadcastReceiver.PendingResult.finish] die eigentliche Frist gegenüber
 * dem System offen hält, während die Arbeit selbst auf [Dispatchers.IO] läuft — `finish()` steht
 * bewusst in einem `finally`-Block, damit ein geworfener Fehler die Frist nie offen lässt.
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

    /**
     * "Neustart nach zu vielen Fehlversuchen" (2026-08-28) — kommt nur an, weil
     * `res/xml/device_admin_receiver.xml` die `watch-login`-Policy deklariert. Der Callback feuert
     * bei jedem Fehlversuch am **System-Sperrbildschirm**; Wardens eigene In-App-PIN läuft
     * unabhängig davon über [de.ble1st.warden.domain.pin.WardenAntiHammeringDecision].
     */
    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        runCatching { FailedAttemptsRebootController(context).onPasswordFailed(BuildConfig.DEBUG) }
            .onFailure { Log.e(TAG, "Fehlversuch-Auswertung fehlgeschlagen", it) }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        runCatching { FailedAttemptsRebootController(context).onPasswordSucceeded() }
            .onFailure { Log.e(TAG, "Fehlversuch-Zähler zurücksetzen fehlgeschlagen", it) }
    }

    override fun onSecurityLogsAvailable(context: Context, intent: Intent) {
        super.onSecurityLogsAvailable(context, intent)
        // Befund Q-4 (2026-08-29): goAsync() statt synchronem Abarbeiten im onReceive-Fenster,
        // s. Klassendoc. appContext statt des Receiver-Parameter-Context, da die Coroutine über
        // das Ende von onReceive() hinaus läuft.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val admin = ComponentName(appContext, WardenDeviceAdminReceiver::class.java)
                val dpm = checkNotNull(appContext.getSystemService(DevicePolicyManager::class.java))
                val events = dpm.retrieveSecurityLogs(admin).orEmpty()
                // 2026-08-28: bis hierher wurde nur die Anzahl protokolliert und der Inhalt
                // verworfen — genau das, was nach einem Vorfall zählt, war damit nirgends
                // einsehbar. Jetzt ausgewertet und persistiert (SecurityEventParser/
                // SecurityEventStore); die Zeile im Audit-Log bleibt als Batch-Nachweis erhalten.
                val records = SecurityEventParser.parseSecurityEvents(events)
                wardenSecurityEvents(appContext).append(records)
                wardenAuditLog(appContext).append(
                    Log.INFO,
                    TAG,
                    "Sicherheits-Log-Batch verarbeitet: ${records.size} Ereignisse gespeichert",
                )
            } catch (e: Exception) {
                Log.e(TAG, "Sicherheits-Log-Abruf fehlgeschlagen", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onNetworkLogsAvailable(context: Context, intent: Intent, batchToken: Long, networkLogsCount: Int) {
        super.onNetworkLogsAvailable(context, intent, batchToken, networkLogsCount)
        // Befund Q-4 (2026-08-29): dasselbe goAsync()-Muster wie onSecurityLogsAvailable oben.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val admin = ComponentName(appContext, WardenDeviceAdminReceiver::class.java)
                val dpm = checkNotNull(appContext.getSystemService(DevicePolicyManager::class.java))
                val events = dpm.retrieveNetworkLogs(admin, batchToken).orEmpty()
                // 2026-08-28: strukturiertes Parsing/Speichern wieder da — anders als der mit der
                // pausierten Netz-Sperre entfernte NetworkEventLogStore ohne jeden Bezug zum
                // VPN-Feature: dieselbe Senke wie für die Sicherheitsereignisse
                // (SecurityEventStore), nur mit eigenen Ereignistypen.
                val records = SecurityEventParser.parseNetworkEvents(events)
                wardenSecurityEvents(appContext).append(records)
                wardenAuditLog(appContext).append(
                    Log.INFO,
                    TAG,
                    "Netzwerk-Log-Batch verarbeitet: ${records.size} Ereignisse gespeichert (token=$batchToken)",
                )
            } catch (e: Exception) {
                Log.e(TAG, "Netzwerk-Log-Abruf fehlgeschlagen", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "WardenDeviceAdmin"
    }
}
