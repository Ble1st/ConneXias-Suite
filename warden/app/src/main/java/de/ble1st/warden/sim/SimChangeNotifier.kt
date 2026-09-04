package de.ble1st.warden.sim

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
 * Benachrichtigung zu einem erkannten SIM-Wechsel (2026-08-28) — eigener Kanal statt einer der
 * drei Bedrohungs-Kanäle aus [de.ble1st.warden.appmanagement.SuspiciousAppNotifier]: das hier ist
 * kein App-Fund, sondern ein Geräte-Ereignis, und es soll auch dann sichtbar bleiben, wenn jemand
 * die Sicherheits-Warnungen stummgeschaltet hat.
 *
 * `IMPORTANCE_HIGH`, weil die Meldung genau einen Zweck hat: die Besitzerin soll sie sehen, wenn
 * sie das Gerät wieder in die Hand bekommt — und ein Angreifer soll merken, dass der Tausch
 * bemerkt wurde. Der Eintrag im Audit-Log ([SimChangeController]) ist der belastbare Teil, diese
 * Benachrichtigung nur der sichtbare.
 */
class SimChangeNotifier(private val context: Context) {

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_sim_change_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_sim_change_channel_description)
        }
        manager?.createNotificationChannel(channel)
    }

    fun notifyChange(simRemoved: Boolean, reactionText: String) {
        val title = context.getString(
            if (simRemoved) R.string.notification_sim_change_removed_title else R.string.notification_sim_change_swapped_title,
        )
        val text = buildString {
            append(
                context.getString(
                    if (simRemoved) R.string.notification_sim_change_removed_text else R.string.notification_sim_change_swapped_text,
                ),
            )
            append(" ")
            append(reactionText)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, WardenStatusActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        // Explizites try/catch statt runCatching — Lints MissingPermission-Prüfung verlangt genau
        // diese Form (s. CLAUDE.md).
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "SIM-Wechsel-Benachrichtigung nicht zustellbar", e)
        }
    }

    private companion object {
        const val TAG = "SimChangeNotifier"
        const val CHANNEL_ID = "warden_sim_change"
        const val NOTIFICATION_ID = 4711
    }
}
