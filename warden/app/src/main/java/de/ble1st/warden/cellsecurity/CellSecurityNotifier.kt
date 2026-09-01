package de.ble1st.warden.cellsecurity

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.ble1st.warden.R
import de.ble1st.warden.domain.appmanagement.ThreatSeverity
import de.ble1st.warden.domain.cellsecurity.CellSecurityOutcome
import de.ble1st.warden.ui.WardenStatusActivity

/**
 * Benachrichtigung zu einer erkannten Mobilfunkzellen-Auffälligkeit (2026-08-29) — eigener Kanal,
 * dieselbe Begründung wie [de.ble1st.warden.sim.SimChangeNotifier]: ein Geräte-Ereignis, kein
 * App-Fund, soll auch dann sichtbar bleiben, wenn die Sicherheits-Warnungen stummgeschaltet sind.
 */
class CellSecurityNotifier(private val context: Context) {

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_cell_security_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_cell_security_channel_description)
        }
        manager?.createNotificationChannel(channel)
    }

    fun notify(outcome: CellSecurityOutcome.Suspicious, reactionText: String) {
        val title = context.getString(
            if (outcome.severity == ThreatSeverity.CRITICAL) {
                R.string.notification_cell_security_critical_title
            } else {
                R.string.notification_cell_security_warning_title
            },
        )
        val indicatorText = outcome.indicators.joinToString(", ") { it.label }
        val text = "$indicatorText. $reactionText"
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
            Log.w(TAG, "Zellen-Auffälligkeits-Benachrichtigung nicht zustellbar", e)
        }
    }

    private companion object {
        const val TAG = "CellSecurityNotifier"
        const val CHANNEL_ID = "warden_cell_security"
        const val NOTIFICATION_ID = 4712
    }
}
