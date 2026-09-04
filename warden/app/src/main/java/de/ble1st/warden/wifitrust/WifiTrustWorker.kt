package de.ble1st.warden.wifitrust

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager-Glue für [WifiTrustController] (2026-09-03), dasselbe Muster wie
 * [de.ble1st.warden.cellsecurity.CellSecurityWorker] — inklusive derselben Begründung für die
 * 15-Minuten-Periode (WorkManagers erzwungenes Minimum).
 */
class WifiTrustWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        WifiTrustController(applicationContext).checkAndMaybeReact()
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "wifi-trust-check"
        private const val POLL_INTERVAL_MINUTES = 15L

        /** Idempotent (`KEEP`), sicher bei jedem Prozessstart erneut aufzurufen. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WifiTrustWorker>(
                POLL_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
