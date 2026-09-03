package de.ble1st.warden.clipboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.ble1st.warden.R
import de.ble1st.warden.domain.clipboard.SensitiveContentDetector
import de.ble1st.warden.ui.WardenStatusActivity

/**
 * "Sensible-Einfügung-Alarm" (2026-09-03) — dieselbe Struktur wie
 * `de.ble1st.warden.sim.SimChangeNotifier`: eigener Kanal, `IMPORTANCE_HIGH`, weil diese Meldung
 * genau einen Moment betrifft, den der Nutzer sofort sehen soll ("du hast gerade etwas Sensibles
 * in einer fremden App eingefügt"), unabhängig davon, ob Sicherheits-Warnungen sonst
 * stummgeschaltet sind.
 *
 * **Trägt nie den erfassten Text selbst**, nur die Kategorie — dieselbe Zurückhaltung wie
 * [ClipboardAccessController]s Metadaten-only-Audit-Log-Zeile: die Benachrichtigung selbst würde
 * sonst auf einem Sperrbildschirm oder in der Benachrichtigungs-Historie genau den sensiblen Wert
 * im Klartext zeigen, den sie eigentlich meldet.
 */
class ClipboardSensitiveContentNotifier(private val context: Context) {

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_clipboard_sensitive_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_clipboard_sensitive_channel_description)
        }
        manager?.createNotificationChannel(channel)
    }

    fun notify(category: SensitiveContentDetector.Category, appLabel: String) {
        val categoryLabel = context.getString(
            when (category) {
                SensitiveContentDetector.Category.SEED_PHRASE_LIKE -> R.string.clipboard_sensitive_category_seed_phrase
                SensitiveContentDetector.Category.CREDIT_CARD_LIKE -> R.string.clipboard_sensitive_category_credit_card
                SensitiveContentDetector.Category.API_KEY_LIKE -> R.string.clipboard_sensitive_category_api_key
            },
        )
        val text = context.getString(R.string.clipboard_sensitive_notification_text, categoryLabel, appLabel)
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
            .setContentTitle(context.getString(R.string.clipboard_sensitive_notification_title))
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
            Log.w(TAG, "Sensible-Einfügung-Benachrichtigung nicht zustellbar", e)
        }
    }

    private companion object {
        const val TAG = "ClipboardSensitiveNotifier"
        const val CHANNEL_ID = "warden_clipboard_sensitive"

        // Eigene ID, nicht die von CellSecurityNotifier/SimChangeNotifier übernommene 4712/4711 —
        // notify(id, ...) ohne Tag teilt sich einen einzigen Slot pro ID innerhalb der App; zwei
        // Notifier mit derselben ID würden sich in der Benachrichtigungsleiste gegenseitig
        // überschreiben statt nebeneinander zu erscheinen (2026-09-03 beim Hinzufügen von
        // WifiTrustNotifier bemerkt und hier korrigiert).
        const val NOTIFICATION_ID = 4713
    }
}
