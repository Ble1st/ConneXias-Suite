package de.ble1st.warden.performance

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Performance-Monitoring-Fenster (2026-08-25, "Lokale Statistik-Erfassung ... BatteryManager").
 * WorkManager-Glue für [BatteryHistoryStore], dasselbe Muster wie
 * [de.ble1st.warden.appmanagement.SuspiciousAppScanWorker]/[de.ble1st.warden.usb
 * .UsbAutoLockWorker] (dortige Klassendocs für die `Worker`-statt-`CoroutineWorker`-/
 * `KEEP`-Policy-Begründung). 30- statt 15-Minuten-Intervall (WorkManagers erzwungenes Minimum
 * wäre 15) — eine Drain-Rate-Schätzung braucht keine höhere Auflösung, ein selteneres Polling
 * spart Batterie für genau die Messung, die es selbst erheben soll.
 */
class BatterySamplingWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val snapshot = BatteryStatusReader(applicationContext).read() ?: return Result.success()
        BatteryHistoryStore(applicationContext).record(
            timestampMillis = System.currentTimeMillis(),
            percent = snapshot.percent,
            charging = snapshot.charging,
        )
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "battery-sampling"
        private const val POLL_INTERVAL_MINUTES = 30L

        /** Idempotent (`KEEP`) — sicher bei jedem Prozessstart erneut aufzurufen
         * ([de.ble1st.warden.WardenApplication]), legt nie doppelte periodische Arbeit an. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BatterySamplingWorker>(
                POLL_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
