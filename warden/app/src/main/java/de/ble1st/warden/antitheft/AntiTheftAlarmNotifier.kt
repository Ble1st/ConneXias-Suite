package de.ble1st.warden.antitheft

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
 * Benachrichtigung zu einem ausgelösten Diebstahlschutz-Alarm (2026-09-03) — eigener Kanal,
 * dieselbe Begründung wie [de.ble1st.warden.cellsecurity.CellSecurityNotifier]. Anders als dort
 * trägt diese Benachrichtigung **keine Stopp-Aktion**: ein Tap auf eine Notification-Aktion ist auf
 * manchen Geräten auch vom Sperrbildschirm aus ohne Entsperrung möglich, was genau den Schutz
 * aushebeln würde, den dieses Feature bieten soll (s. [AntiTheftAlarmController]s Klassendoc — der
 * Alarm endet ausschließlich durch eine echte Entsperrung).
 */
class AntiTheftAlarmNotifier(private val context: Context) {

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_anti_theft_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_anti_theft_channel_description)
        }
        manager?.createNotificationChannel(channel)
    }

    fun notify(reasonText: String) {
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
            .setContentTitle(context.getString(R.string.notification_anti_theft_title))
            .setContentText(reasonText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reasonText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()

        // Explizites try/catch statt runCatching — Lints MissingPermission-Prüfung verlangt genau
        // diese Form (s. CLAUDE.md).
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Diebstahlschutz-Alarm-Benachrichtigung nicht zustellbar", e)
        }
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val TAG = "AntiTheftAlarmNotifier"
        const val CHANNEL_ID = "warden_anti_theft"

        // s. de.ble1st.warden.clipboard.ClipboardSensitiveContentNotifier für die Begründung der
        // eigenen ID je Notifier.
        const val NOTIFICATION_ID = 4715
    }
}
