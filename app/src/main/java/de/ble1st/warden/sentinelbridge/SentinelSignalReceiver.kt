package de.ble1st.warden.sentinelbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.ble1st.warden.WardenApplication
import de.ble1st.warden.wardenAuditLog

/**
 * "Sentinel: eigenständige Kiosk-PIN-App" (2026-08-26) — Sentinel→Warden-Richtung, das Entwarn-
 * Signal aus [SentinelActivity][de.ble1st.warden.sentinel.SentinelActivity]s
 * `onExitLockdown`-Zweig (fremde APK, korrekte Sentinel-eigene PIN während aktivem Lock-Task).
 * Geschützt durch die von Warden selbst deklarierte
 * `de.ble1st.warden.permission.SENTINEL_SIGNAL`-`signature`-Permission
 * (`app/src/main/AndroidManifest.xml`) — nur ein mit demselben Zertifikat signiertes Paket
 * (Sentinel) kann diesen Broadcast überhaupt zustellen, von Android selbst durchgesetzt, bevor
 * dieser Code je läuft (Plan-Abschnitt "Warum kein AIDL-Bus").
 *
 * **Kein Ersatz für Sentinels eigenen `stopLockTask()`-Aufruf:** [SentinelActivity] ruft diesen
 * lokal und unabhängig vom Erfolg dieses Broadcasts auf (Fail-Safe: ein deinstalliertes/
 * abgestürztes Warden darf den einzigen Ausweg aus dem Kiosk nicht blockieren, s. dessen
 * Klassendoc) — dieser Receiver zieht nur Wardens eigene DPM-Whitelist-Autorisierung zurück und
 * beendet den Watchdog, beides überflüssig, sobald Sentinel den Kiosk-Zustand ohnehin schon
 * lokal verlassen hat, aber nicht schädlich, falls dieser Broadcast (unwahrscheinlich) vor dem
 * lokalen `stopLockTask()` ankommt.
 *
 * `exported="true"` ist für einen `signature`-geschützten, fremd-adressierten Empfänger Pflicht
 * (dieselbe Begründung wie bei [de.ble1st.warden.admin.WardenDeviceAdminReceiver]) — die
 * eigentliche Zugriffskontrolle liegt im `android:permission`-Attribut, nicht in `exported`
 * selbst.
 */
class SentinelSignalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PIN_VERIFIED) return
        val logStore = wardenAuditLog(context)
        logStore.append(Log.WARN, TAG, "Entwarn-Signal von Sentinel empfangen (PIN verifiziert)")
        val controller = (context.applicationContext as WardenApplication).sentinelWatchdogController
        runCatching { controller.disarm() }
            .onFailure { logStore.append(Log.ERROR, TAG, "Sentinel-Wächter-Entschärfung nach Entwarn-Signal fehlgeschlagen: $it") }
    }

    companion object {
        /** Muss mit `de.ble1st.warden.sentinel.SentinelActivity.ACTION_PIN_VERIFIED`
         * übereinstimmen (kein gemeinsames Modul für diese eine Konstante — bewusst, s. Plan). */
        const val ACTION_PIN_VERIFIED = "de.ble1st.warden.sentinel.action.PIN_VERIFIED"

        private const val TAG = "SentinelSignalReceiver"
    }
}
