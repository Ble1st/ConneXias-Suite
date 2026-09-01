package de.ble1st.warden.profile

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager-Glue für [AutoProfileController] (2026-08-28), dasselbe Muster wie
 * [de.ble1st.warden.autoreboot.AutoRebootWorker].
 *
 * 15 Minuten sind hier zugleich die Genauigkeit des Zeitplans: ein Nachtfenster, das um 22:00
 * beginnt, greift also spätestens um 22:15. Für ein Härtungsprofil ist das dieselbe vertretbare
 * Toleranz wie beim Auto-Reboot-Zeitfenster; ein exakter Wecker (`AlarmManager`) wäre unter Doze
 * unzuverlässiger als der ohnehin laufende periodische Lauf.
 */
class AutoProfileWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        AutoProfileController(applicationContext).checkAndMaybeSwitch()
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "auto-profile-check"
        private const val POLL_INTERVAL_MINUTES = 15L

        /** Idempotent (`KEEP`), sicher bei jedem Prozessstart erneut aufzurufen. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoProfileWorker>(
                POLL_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
