package de.ble1st.warden.sentinelbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.ble1st.warden.WardenApplication
import de.ble1st.warden.wardenAuditLog

/**
 * Sentinel → Warden. Geschützt durch `de.ble1st.warden.permission.SENTINEL_SIGNAL`
 * (`signature`-Level, im Manifest deklariert) — Android setzt das am Zertifikat durch, bevor
 * überhaupt App-Code läuft, deshalb ist hier kein eigener Aufrufer-Verifier nötig
 * (Plan-Abschnitt "Warum kein AIDL-Bus").
 *
 * Zwei der drei Actions haben dieselbe Reaktion — den Wächter entschärfen und die
 * DPM-Lock-Task-Whitelist zurückziehen:
 *
 * - [ACTION_PIN_VERIFIED] — Entwarnung: korrekte Sentinel-PIN während aktivem Kiosk. Sentinel hat
 *   `stopLockTask()` bereits selbst gerufen, unabhängig davon, ob dieser Broadcast ankam.
 * - [ACTION_ENGAGE_REFUSED] (2026-08-28) — Sentinel hat das Scharfschalten **abgelehnt** (kein
 *   bestätigter Notruf-Drill, keine benutzbare Sentinel-PIN) oder einen wegen beschädigtem
 *   PIN-Blob unverlassbaren Kiosk aufgegeben. Ohne diesen Weg bliebe Warden in genau dem Zustand
 *   zurück, den [SentinelWatchdogController.arm] herstellt — Watchdog gebunden, Sentinels Paket
 *   auf der Whitelist — während faktisch kein Kiosk läuft.
 *   `SentinelLockdownEngager.engage()` kann das nicht selbst bemerken: es fängt nur
 *   `ActivityNotFoundException`, ein gestarteter aber ablehnender Sentinel sieht von dort aus wie
 *   ein Erfolg aus.
 *
 * Die dritte, [ACTION_PIN_STATE] (Vorschlag U-8, 2026-08-29), ist bewusst **anders**: sie
 * entschärft den Wächter *nicht* und schreibt auch keinen Audit-Eintrag. Sie ist eine reine
 * Zustandsmeldung, die Sentinel bei jedem Öffnen und Verlassen unaufgefordert schickt, damit
 * Warden die Kiosk-Vorbedingung "Sentinel-PIN eingerichtet" anzeigen kann, statt sie erst im
 * Ernstfall über [ACTION_ENGAGE_REFUSED] zu erfahren. Ein Audit-Eintrag pro Sentinel-Aufruf wäre
 * derselbe Fehler wie die 2026-08-28 entfernten Lese-Einträge: er würde die Einträge verdrängen,
 * für die das Log da ist. Der Wächter darf sie schon deshalb nicht sehen, weil sie über einen
 * laufenden Kiosk nichts aussagt — sie kommt gerade auch *während* eines aktiven Kiosks an.
 */
class SentinelSignalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Vor allem anderen und mit eigenem `return`: diese Action darf weder ins Audit-Log noch
        // an die Wächter-Entschärfung unten (s. Klassendoc).
        if (intent.action == ACTION_PIN_STATE) {
            // `false` als Default ist hier die sichere Richtung: ein Broadcast ohne das Extra ist
            // ein Fehler auf Sentinels Seite, und "PIN fehlt" führt zu einer Warnung in der UI,
            // "PIN vorhanden" zu einer falschen Beruhigung.
            SentinelPinStateStore.record(context, intent.getBooleanExtra(EXTRA_PIN_CONFIGURED, false))
            return
        }
        val logStore = wardenAuditLog(context)
        when (intent.action) {
            ACTION_PIN_VERIFIED ->
                logStore.append(Log.WARN, TAG, "Entwarn-Signal von Sentinel empfangen (PIN verifiziert)")

            ACTION_ENGAGE_REFUSED -> {
                // Grund kommt aus einem signature-geschützten Broadcast, also aus dem eigenen
                // Zertifikatskreis — trotzdem gekürzt ins Log, damit ein unerwartet langer Wert
                // keinen Log-Eintrag aufbläht (das Log wird bei jedem append komplett neu
                // geschrieben, s. HashChainLogStore).
                val reason = intent.getStringExtra(EXTRA_REFUSAL_REASON)?.take(MAX_REASON_LENGTH)
                    ?: "ohne Angabe"
                logStore.append(
                    Log.ERROR,
                    TAG,
                    "Sentinel hat das Scharfschalten abgelehnt: $reason — Kiosk läuft NICHT, " +
                        "Wächter wird entschärft",
                )
            }

            else -> return
        }
        val controller = (context.applicationContext as WardenApplication).sentinelWatchdogController
        runCatching { controller.disarm() }
            .onFailure { logStore.append(Log.ERROR, TAG, "Sentinel-Wächter-Entschärfung fehlgeschlagen: $it") }
    }

    companion object {
        const val ACTION_PIN_VERIFIED = "de.ble1st.warden.sentinel.action.PIN_VERIFIED"

        const val ACTION_ENGAGE_REFUSED = "de.ble1st.warden.sentinel.action.ENGAGE_REFUSED"

        const val EXTRA_REFUSAL_REASON = "refusalReason"

        /** Muss mit `de.ble1st.warden.sentinel.SentinelActivity.ACTION_PIN_STATE` übereinstimmen. */
        const val ACTION_PIN_STATE = "de.ble1st.warden.sentinel.action.PIN_STATE"

        const val EXTRA_PIN_CONFIGURED = "pinConfigured"

        private const val MAX_REASON_LENGTH = 200

        private const val TAG = "SentinelSignalReceiver"
    }
}
