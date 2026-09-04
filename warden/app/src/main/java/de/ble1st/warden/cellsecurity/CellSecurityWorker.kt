package de.ble1st.warden.cellsecurity

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import de.ble1st.warden.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * WorkManager-Glue für [CellSecurityController] (2026-08-29), dasselbe Muster wie
 * [de.ble1st.warden.sim.SimChangeWorker] — inklusive derselben Begründung für die 15-Minuten-
 * Periode (WorkManagers erzwungenes Minimum; ein tatsächlicher IMSI-Catcher-Einsatz kann kürzer
 * als 15 Minuten dauern, das ist eine strukturelle Grenze dieses Mechanismus, keine bewusste
 * Kompromisswahl).
 */
class CellSecurityWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        CellSecurityController(applicationContext).checkAndMaybeReact(BuildConfig.DEBUG)
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "cell-security-check"
        private const val POLL_INTERVAL_MINUTES = 15L

        /** Idempotent (`KEEP`), sicher bei jedem Prozessstart erneut aufzurufen. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CellSecurityWorker>(
                POLL_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
