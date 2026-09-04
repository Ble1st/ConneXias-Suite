package de.ble1st.warden.sentinelbridge

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import de.ble1st.warden.WardenApplication

/**
 * "Sentinel: eigenständige Kiosk-PIN-App" — der einzige Aufrufpfad, der Sentinel real
 * scharfschaltet. Ersetzt das frühere `de.ble1st.warden.pin.WardenLockTaskManager`
 * (`startLockTask()` auf Wardens eigenem Paket) — jetzt zweistufig:
 *
 * 1. [SentinelWatchdogController.arm] whitelistet Sentinels Paket real für Lock-Task
 *    (`WardenLockTaskAuthorizer.apply`, `setLockTaskPackages`/`setLockTaskFeatures`) UND startet
 *    den Cross-Process-Death-Watchdog ([SentinelDeathWatchdog], s. dessen Klassendoc) — beides
 *    idempotent, jederzeit risikolos wiederholbar.
 * 2. Ein expliziter `startActivity()` auf [SentinelActivity][de.ble1st.warden.sentinel
 *    .SentinelActivity] — `signature`-Permission-geschützt auf Sentinels Manifest-Seite
 *    ([de.ble1st.warden.sentinel.permission.ENGAGE]), kein Userspace-Caller-Verifier nötig (Plan-
 *    Abschnitt "Warum kein AIDL-Bus"). [emergencyCallDrillPassed] kommt vom Aufrufer, gespiegelt
 *    aus Wardens eigenem `WardenLockTaskDrillStorage`-Bit — nie hier hartkodiert `true`, mit der
 *    einzigen bewussten Ausnahme des ADB-Drill-Triggers selbst
 *    ([de.ble1st.warden.presence.WardenPinActivity]s `EXTRA_ENGAGE_LOCK_TASK_DRILL`-Zweig: dieser
 *    Aufruf **ist** der reale, manuell durchgeführte Drill).
 *
 * `startLockTask()` selbst läuft erst in Sentinels eigenem Prozess (`SentinelActivity.onResume`),
 * nicht hier — dieselbe Trennung "Autorisierung hier, tatsächliches Scharfschalten dort" wie im
 * Quellprojekt.
 *
 * [ActivityNotFoundException] ist ein erwarteter, kein struktureller Fehlerfall: Sentinel ist
 * eine separat installierte APK (s. `de.ble1st.warden.appmanagement.SentinelSilentInstaller`) —
 * ein Aufruf, bevor sie je installiert wurde, muss sichtbar fehlschlagen, nicht crashen.
 */
object SentinelLockdownEngager {
    const val SENTINEL_PACKAGE_NAME = "de.ble1st.warden.sentinel"
    private const val SENTINEL_ACTIVITY_CLASS_NAME = "de.ble1st.warden.sentinel.SentinelActivity"
    private const val EXTRA_ENGAGE_LOCKDOWN = "engageLockdown"
    private const val EXTRA_EMERGENCY_CALL_DRILL_PASSED = "emergencyCallDrillPassed"
    private const val TAG = "SentinelLockdownEngager"

    fun engage(context: Context, emergencyCallDrillPassed: Boolean): Boolean {
        (context.applicationContext as WardenApplication).sentinelWatchdogController.arm()
        return try {
            context.startActivity(
                Intent()
                    .setClassName(SENTINEL_PACKAGE_NAME, SENTINEL_ACTIVITY_CLASS_NAME)
                    .putExtra(EXTRA_ENGAGE_LOCKDOWN, true)
                    .putExtra(EXTRA_EMERGENCY_CALL_DRILL_PASSED, emergencyCallDrillPassed)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Sentinel nicht installiert — Scharfschalten abgebrochen", e)
            false
        }
    }
}
