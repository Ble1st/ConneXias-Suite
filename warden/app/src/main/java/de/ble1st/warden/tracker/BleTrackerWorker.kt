package de.ble1st.warden.tracker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager-Glue für [BleTrackerController] (2026-09-03) — 15-Minuten-Periode wie die übrigen
 * lokalen Trigger (WorkManagers erzwungenes Minimum). Kein Startup-Sofortlauf: ein frisch
 * gestartetes Gerät braucht keine sofortige BLE-Prüfung, anders als z. B. SIM-Wechsel direkt nach
 * einem verdächtigen Neustart.
 */
class BleTrackerWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        BleTrackerController(applicationContext).checkOnce()
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "ble-tracker-check"
        private const val POLL_INTERVAL_MINUTES = 15L

        /** Idempotent (`KEEP`), sicher bei jedem Prozessstart erneut aufzurufen. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BleTrackerWorker>(
                POLL_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
