package de.ble1st.warden.failedattempts

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import de.ble1st.warden.admin.WardenDeviceAdminReceiver
import de.ble1st.warden.domain.failedattempts.FailedAttemptsRebootDecision
import de.ble1st.warden.domain.presence.DestructiveCommandGuard
import de.ble1st.warden.wardenAuditLog

/**
 * Android-Glue für [FailedAttemptsRebootDecision] (2026-08-28) — angestoßen aus
 * [WardenDeviceAdminReceiver]s `onPasswordFailed`/`onPasswordSucceeded`, die Android nur
 * zustellt, weil `res/xml/device_admin_receiver.xml` die `watch-login`-Policy deklariert (ohne
 * den Eintrag kommt der Callback schlicht nie an — dieselbe Klasse von Fund wie bei
 * `disable-keyguard-features`, s. dortigen Kommentar).
 *
 * **Zähler-Semantik:** [onPasswordFailed] erhöht und wertet aus, [onPasswordSucceeded] setzt
 * zurück — nur *aufeinanderfolgende* Fehlversuche zählen, wie am Sperrbildschirm gewohnt. Der
 * Zähler wird nach einem ausgelösten Neustart **nicht** zurückgesetzt: bleibt das Gerät in
 * fremden Händen, soll auch der nächste Fehlversuch wieder zum Neustart führen, statt dem
 * Angreifer nach jedem Reboot ein frisches Kontingent zu schenken. Zurückgesetzt wird
 * ausschließlich durch eine erfolgreiche Entsperrung — also durch die Besitzerin.
 *
 * **Debug-Build-Hardblock gilt auch hier:** dieselbe Haltung wie
 * [de.ble1st.warden.domain.pin.WardenLockTaskAutoEngageDecision] — ein automatischer,
 * presence-loser Auslöser darf auf einem Entwicklungsgerät keine echte DPM-Aktion feuern, sonst
 * ist jede Fehleingabe beim Testen ein Neustart mitten in der Sitzung.
 */
class FailedAttemptsRebootController(private val context: Context) {

    private val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)

    fun onPasswordFailed(isDebugBuild: Boolean) {
        val threshold = FailedAttemptsRebootStorage.loadThreshold(context) ?: return
        val attempts = FailedAttemptsRebootStorage.loadFailedAttempts(context) + 1
        FailedAttemptsRebootStorage.saveFailedAttempts(context, attempts)

        if (!FailedAttemptsRebootDecision.shouldReboot(threshold, attempts)) return

        val logStore = wardenAuditLog(context)
        if (!DestructiveCommandGuard.isExecutionAllowed(isDebugBuild)) {
            logStore.append(
                Log.WARN,
                TAG,
                "Neustart nach $attempts Fehlversuchen unterdrückt — Debug-Build (Schwelle: $threshold)",
            )
            return
        }
        try {
            val dpm = checkNotNull(context.getSystemService(DevicePolicyManager::class.java))
            logStore.append(Log.WARN, TAG, "Neustart nach $attempts Fehlversuchen am Sperrbildschirm (Schwelle: $threshold)")
            dpm.reboot(admin)
        } catch (e: Exception) {
            logStore.append(Log.ERROR, TAG, "Neustart nach Fehlversuchen fehlgeschlagen: $e")
        }
    }

    fun onPasswordSucceeded() {
        // Unbedingt zurücksetzen, auch wenn die Funktion gerade aus ist — sonst stünde beim
        // späteren Einschalten ein alter, längst überholter Zählerstand bereit.
        FailedAttemptsRebootStorage.resetFailedAttempts(context)
    }

    private companion object {
        const val TAG = "FailedAttemptsReboot"
    }
}
