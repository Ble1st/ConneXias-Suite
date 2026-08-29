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
 * Zwei Actions, beide mit derselben Reaktion — den Wächter entschärfen und die
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
 */
class SentinelSignalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
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

        private const val MAX_REASON_LENGTH = 200

        private const val TAG = "SentinelSignalReceiver"
    }
}
