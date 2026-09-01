package de.ble1st.warden.sim

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import de.ble1st.warden.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Verzögerter Erstlauf für [SimChangeController] nach dem Prozessstart (Befund Q-6, 2026-08-29).
 *
 * Vorher rief `WardenApplication.onCreate()` `SimChangeController.checkAndMaybeReact()` synchron
 * und ohne jede Verzögerung auf — genau im frühen Boot-Fenster, in dem
 * `SubscriptionInfo.getCarrierId()` häufig noch `UNKNOWN_CARRIER_ID` liefert, bis die
 * Carrier-Config nachgeladen ist (s. [SimFingerprintReader]-Klassendoc). Ein direkt beim Start
 * gebildeter Fingerabdruck-Snapshot hätte sich dadurch von einem wenig später gebildeten
 * unterscheiden können — ein reines Timing-Artefakt, das [de.ble1st.warden.domain.sim
 * .SimChangeDecision] nicht von einem echten SIM-Wechsel unterscheiden kann.
 *
 * Ein [androidx.work.OneTimeWorkRequest] mit kurzer Startverzögerung statt des synchronen Aufrufs
 * gibt dem System diese Zeit, bleibt dabei aber weit unter den 15 Minuten, bis der erste
 * periodische [SimChangeWorker]-Lauf ohnehin greifen würde — der eigentliche Zweck des
 * Sofortlaufs (ein SIM-Tausch nach einem Neustart soll nicht erst 15 Minuten später auffallen)
 * bleibt damit erhalten.
 */
class SimChangeStartupWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        SimChangeController(applicationContext).checkAndMaybeReact(BuildConfig.DEBUG)
        return Result.success()
    }

    companion object {
        private const val STARTUP_DELAY_SECONDS = 30L

        /** Bewusst kein `enqueueUniqueWork`/`KEEP` wie bei den periodischen Workern — ein
         * einmaliger Lauf pro Prozessstart ist gewollt; ein durch zwei kurz aufeinanderfolgende
         * Prozessstarts doppelt eingereihter Lauf ist harmlos, da `checkAndMaybeReact()` bei
         * unverändertem Fingerabdruck ohnehin nichts tut. */
        fun scheduleOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<SimChangeStartupWorker>()
                .setInitialDelay(STARTUP_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
