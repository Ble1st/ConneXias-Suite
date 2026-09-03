package de.ble1st.warden.score

import android.content.Context
import androidx.core.content.edit

/**
 * Merkt sich nur den Zeitpunkt der letzten gesendeten Erinnerung, für
 * [de.ble1st.warden.domain.score.ScoreReminderDecision]s Entprellungs-Fenster (2026-09-03) — kein
 * schützenswerter Wert, normale `SharedPreferences` wie bei
 * [de.ble1st.warden.score.SecurityScoreHistoryStore].
 */
object ScoreReminderStorage {
    private const val PREFS_NAME = "warden_score_reminder"
    private const val KEY_LAST_REMINDER_AT = "last_reminder_at"

    fun loadLastReminderAt(context: Context): Long? =
        prefs(context).getLong(KEY_LAST_REMINDER_AT, -1L).takeIf { it >= 0L }

    fun saveLastReminderAt(context: Context, atMillis: Long) {
        prefs(context).edit { putLong(KEY_LAST_REMINDER_AT, atMillis) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
