package de.ble1st.warden.appmanagement

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import de.ble1st.warden.WardenApplication
import java.util.concurrent.TimeUnit

/**
 * Milestone "Automatisches Einfrieren verdächtiger Apps", seit "Manifest-Scan + Sofort-
 * Benachrichtigung" (2026-08-21) um [SuspiciousAppScanController.notifyNewFindings] erweitert —
 * dünne WorkManager-Glue: `Worker` (nicht `CoroutineWorker`, `doWork()` ist reiner, schneller,
 * lokaler `DevicePolicyManager`/`AccessibilityManager`/`PackageManager`-Zugriff, kein Netzwerk-/
 * Langzeit-I/O), 15-Minuten-Intervall (WorkManagers erzwungenes Minimum), `KEEP`-Policy (idempotent
 * bei jedem `WardenApplication.onCreate()`-Aufruf). Ein [SuspiciousAppScanController.runImmediateScan]
 * pro Lauf: Evaluate → optional Auto-Freeze → Notify → Baseline-Commit, damit Transition-Signale
 * nicht zwischen zwei `scan()`-Aufrufen verloren gehen.
 */
class SuspiciousAppScanWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val controller = (applicationContext as WardenApplication).suspiciousAppScanController
        controller.runImmediateScan()
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "suspicious-app-scan"
        private const val UNIQUE_IMMEDIATE_WORK_NAME = "suspicious-app-scan-immediate"
        private const val POLL_INTERVAL_MINUTES = 15L

        /** Idempotent (`KEEP`) — sicher bei jedem Prozessstart erneut aufzurufen
         * ([WardenApplication]), legt nie doppelte periodische Arbeit an. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SuspiciousAppScanWorker>(
                POLL_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** [de.ble1st.warden.appmanagement.PackageChangeReceiver]-Klassendoc: einmaliger,
         * sofort laufender Scan statt bis zu 15 Minuten auf den nächsten periodischen [schedule]
         * -Lauf zu warten. Eigener, vom periodischen Poll unabhängiger `UNIQUE_IMMEDIATE_WORK_NAME`
         * (nicht derselbe wie [UNIQUE_WORK_NAME]) mit `REPLACE`-Policy — mehrere Paketänderungen
         * kurz hintereinander (z. B. ein App-Update: `PACKAGE_REMOVED`(replacing)+`PACKAGE_ADDED`)
         * lösen dann nur den jeweils letzten, noch nicht angelaufenen Sofort-Scan aus, statt sich
         * in der Warteschlange zu stapeln; würde derselbe Name wie der periodische Poll verwendet,
         * verschöbe `REPLACE` dessen bereits laufende 15-Minuten-Planung unbeabsichtigt mit. */
        fun scheduleImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<SuspiciousAppScanWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_IMMEDIATE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
