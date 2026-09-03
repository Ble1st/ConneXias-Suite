package de.ble1st.warden.score

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.ble1st.warden.R
import de.ble1st.warden.ui.WardenStatusActivity

/**
 * Erinnerungs-Benachrichtigung für [ScoreReminderController] (2026-09-03) — bewusst
 * `IMPORTANCE_DEFAULT`, nicht `IMPORTANCE_HIGH` wie die übrigen Notifier in diesem Projekt: ein
 * veralteter Score ist kein akutes Sicherheitsereignis, sondern eine Erinnerung ohne Zeitdruck
 * (s. [de.ble1st.warden.domain.score.ScoreReminderDecision]-Klassendoc).
 */
class ScoreReminderNotifier(private val context: Context) {

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_score_reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_score_reminder_channel_description)
        }
        manager?.createNotificationChannel(channel)
    }

    // "notify" wäre eine "Accidental Override" von java.lang.Object.notify() (Thread-Wait/Notify)
    // — anders als bei den übrigen *Notifier-Klassen dieses Projekts, deren notify(...) immer
    // Parameter tragen und damit nicht kollidieren, muss diese Methode hier anders heißen.
    fun send() {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, WardenStatusActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.getString(R.string.notification_score_reminder_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_score_reminder_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        // Explizites try/catch statt runCatching — Lints MissingPermission-Prüfung verlangt genau
        // diese Form (s. CLAUDE.md).
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Score-Erinnerung nicht zustellbar", e)
        }
    }

    private companion object {
        const val TAG = "ScoreReminderNotifier"
        const val CHANNEL_ID = "warden_score_reminder"

        // s. de.ble1st.warden.clipboard.ClipboardSensitiveContentNotifier für die Begründung der
        // eigenen ID je Notifier.
        const val NOTIFICATION_ID = 4716
    }
}
