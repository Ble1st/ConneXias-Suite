package de.ble1st.warden.wifitrust

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import de.ble1st.warden.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Verzögerter Erstlauf für [WifiTrustController] nach dem Prozessstart, dasselbe Muster und
 * dieselbe Begründung wie [de.ble1st.warden.cellsecurity.CellSecurityStartupWorker]: ein kurzer
 * Boot-Verzug gibt dem WLAN-Stack Zeit, eine bereits konfigurierte Verbindung tatsächlich
 * herzustellen, bevor der erste Prüflauf die SSID abfragt.
 */
class WifiTrustStartupWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        WifiTrustController(applicationContext).checkAndMaybeReact(BuildConfig.DEBUG)
        return Result.success()
    }

    companion object {
        private const val STARTUP_DELAY_SECONDS = 30L

        /** Bewusst kein `enqueueUniqueWork`/`KEEP` wie bei den periodischen Workern — ein
         * einmaliger Lauf pro Prozessstart ist gewollt; ein durch zwei kurz aufeinanderfolgende
         * Prozessstarts doppelt eingereihter Lauf ist harmlos. */
        fun scheduleOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<WifiTrustStartupWorker>()
                .setInitialDelay(STARTUP_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
