package de.ble1st.warden.score

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager-Glue für [ScoreReminderController] (2026-09-03) — ein Tageszyklus reicht, anders als
 * die 15-Minuten-Perioden der übrigen lokalen Trigger: eine 30-Tage-Schwelle muss nicht minutengenau
 * geprüft werden, s. [de.ble1st.warden.domain.score.ScoreReminderDecision]-Klassendoc.
 */
class ScoreReminderWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        ScoreReminderController(applicationContext).checkAndMaybeRemind()
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "score-reminder-check"
        private const val POLL_INTERVAL_DAYS = 1L

        /** Idempotent (`KEEP`), sicher bei jedem Prozessstart erneut aufzurufen. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScoreReminderWorker>(
                POLL_INTERVAL_DAYS,
                TimeUnit.DAYS,
            ).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
