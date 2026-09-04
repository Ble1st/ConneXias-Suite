package de.ble1st.warden.tracker

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
 * Benachrichtigung zu einem möglicherweise mitlaufenden BLE-Gerät (2026-09-03) — eigener Kanal,
 * `IMPORTANCE_HIGH` wie [de.ble1st.warden.cellsecurity.CellSecurityNotifier]: dieselbe
 * "Verdachtssignal, das der Nutzer selbst einschätzen muss"-Dringlichkeit.
 */
class BleTrackerNotifier(private val context: Context) {

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_ble_tracker_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_ble_tracker_channel_description)
        }
        manager?.createNotificationChannel(channel)
    }

    fun send(address: String, findMyShaped: Boolean, sightingCount: Int) {
        val title = context.getString(
            if (findMyShaped) R.string.notification_ble_tracker_findmy_title else R.string.notification_ble_tracker_generic_title,
        )
        val text = context.getString(R.string.notification_ble_tracker_text, address, sightingCount)
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
            Log.w(TAG, "BLE-Tracker-Benachrichtigung nicht zustellbar", e)
        }
    }

    private companion object {
        const val TAG = "BleTrackerNotifier"
        const val CHANNEL_ID = "warden_ble_tracker"

        // s. de.ble1st.warden.clipboard.ClipboardSensitiveContentNotifier für die Begründung der
        // eigenen ID je Notifier.
        const val NOTIFICATION_ID = 4717
    }
}
