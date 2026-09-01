package de.ble1st.warden.sim

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import de.ble1st.warden.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * WorkManager-Glue für [SimChangeController] (2026-08-28), dasselbe Muster wie
 * [de.ble1st.warden.autoreboot.AutoRebootWorker].
 *
 * **15 Minuten** (WorkManagers erzwungenes Minimum) statt eines sparsameren Intervalls: anders als
 * die Akku-Messreihe ist das hier ein Diebstahl-Auslöser — die Zeitspanne zwischen SIM-Tausch und
 * Reaktion ist genau die Zeit, in der das Gerät noch erreichbar/entsperrbar ist. Der Lauf selbst
 * ist billig (ein `SubscriptionManager`-Aufruf und ein String-Vergleich, kein Netz, keine I/O).
 */
class SimChangeWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        SimChangeController(applicationContext).checkAndMaybeReact(BuildConfig.DEBUG)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "sim-change-check"
        private const val POLL_INTERVAL_MINUTES = 15L

        /** Idempotent (`KEEP`), sicher bei jedem Prozessstart erneut aufzurufen. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SimChangeWorker>(
                POLL_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
