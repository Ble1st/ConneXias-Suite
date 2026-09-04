package de.ble1st.warden.wifitrust

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
 * Benachrichtigung zu einem unbekannten WLAN (2026-09-03) — eigener Kanal, dieselbe Begründung wie
 * [de.ble1st.warden.cellsecurity.CellSecurityNotifier]: ein Geräte-Ereignis, kein App-Fund, soll
 * auch dann sichtbar bleiben, wenn die Sicherheits-Warnungen stummgeschaltet sind.
 */
class WifiTrustNotifier(private val context: Context) {

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_wifi_trust_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_wifi_trust_channel_description)
        }
        manager?.createNotificationChannel(channel)
    }

    fun notify(ssid: String, reactionText: String) {
        val text = context.getString(R.string.notification_wifi_trust_text, ssid, reactionText)
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
            .setContentTitle(context.getString(R.string.notification_wifi_trust_title))
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
            Log.w(TAG, "WLAN-Vertrauens-Benachrichtigung nicht zustellbar", e)
        }
    }

    private companion object {
        const val TAG = "WifiTrustNotifier"
        const val CHANNEL_ID = "warden_wifi_trust"

        // s. ClipboardSensitiveContentNotifier für die Begründung der eigenen ID.
        const val NOTIFICATION_ID = 4714
    }
}
