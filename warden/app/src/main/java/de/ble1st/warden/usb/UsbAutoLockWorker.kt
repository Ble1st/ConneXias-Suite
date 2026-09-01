package de.ble1st.warden.usb

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager-Glue für [UsbAutoLockController], dasselbe Muster wie
 * [de.ble1st.warden.autoreboot.AutoRebootWorker]/[de.ble1st.warden.appmanagement
 * .SuspiciousAppScanWorker] (dortige Klassendocs für die 15-Minuten-Intervall-/`Worker`-statt-
 * `CoroutineWorker`-/`KEEP`-Policy-Begründung — identisch hier: reiner, schneller, lokaler
 * `KeyguardManager`/`DevicePolicyManager`-Zugriff, kein Netzwerk-/Langzeit-I/O).
 */
class UsbAutoLockWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        UsbAutoLockController(applicationContext).checkAndSync()
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "usb-auto-lock-check"
        private const val POLL_INTERVAL_MINUTES = 15L

        /** Idempotent (`KEEP`) — sicher bei jedem Prozessstart erneut aufzurufen
         * ([de.ble1st.warden.WardenApplication]), legt nie doppelte periodische Arbeit an. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UsbAutoLockWorker>(
                POLL_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
