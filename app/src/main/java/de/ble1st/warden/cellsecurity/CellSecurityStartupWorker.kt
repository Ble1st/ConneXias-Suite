package de.ble1st.warden.cellsecurity

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import de.ble1st.warden.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Verzögerter Erstlauf für [CellSecurityController] nach dem Prozessstart, dasselbe Muster und
 * dieselbe Begründung wie [de.ble1st.warden.sim.SimChangeStartupWorker]: ein kurzer Boot-Verzug
 * gibt der Telefonie-/Standort-Stack Zeit, sich vollständig zu initialisieren, bevor der erste
 * Messwert genommen wird — ein zu früh gebildeter, noch unvollständiger Messwert dürfte sich sonst
 * von einem kurz danach gebildeten unterscheiden, ein reines Timing-Artefakt statt einer echten
 * Auffälligkeit.
 */
class CellSecurityStartupWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        CellSecurityController(applicationContext).checkAndMaybeReact(BuildConfig.DEBUG)
        return Result.success()
    }

    companion object {
        private const val STARTUP_DELAY_SECONDS = 30L

        /** Bewusst kein `enqueueUniqueWork`/`KEEP` wie bei den periodischen Workern — ein
         * einmaliger Lauf pro Prozessstart ist gewollt; ein durch zwei kurz aufeinanderfolgende
         * Prozessstarts doppelt eingereihter Lauf ist harmlos. */
        fun scheduleOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<CellSecurityStartupWorker>()
                .setInitialDelay(STARTUP_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
