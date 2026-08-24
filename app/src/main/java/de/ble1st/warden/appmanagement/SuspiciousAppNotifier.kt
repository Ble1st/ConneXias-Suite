package de.ble1st.warden.appmanagement

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.ble1st.warden.R
import de.ble1st.warden.domain.appmanagement.SuspiciousSignal

/**
 * Milestone "Manifest-Scan + Sofort-Benachrichtigung" (2026-08-21, auf Nutzerwunsch: "bei Fund
 * soll eine Benachrichtigung auf die Gefahr hinweisen mit Option, die App sofort zu
 * freezen/deinstallieren"). Läuft über [SuspiciousAppScanController.notifyNewFindings] —
 * unabhängig vom "Automatisch einfrieren"-Schalter, dieselbe Transparenz-Haltung wie
 * [SuspiciousAppScanController.scan] ("was würde der nächste Lauf einfrieren"): eine
 * Benachrichtigung mit expliziten Aktionsknöpfen fragt den Nutzer *vor* jeder Aktion, ist also
 * nie überraschend — anders als das stille, deshalb bewusst Opt-in gehaltene Auto-Einfrieren.
 *
 * Eine Benachrichtigung pro Paket (`tag=packageName`, feste `id`), [NotificationCompat.Builder
 * .setOnlyAlertOnce] verhindert, dass ein noch unbehandelter Fund bei jedem 15-Minuten-Scan
 * erneut piept/aufploppt — sie bleibt einfach bestehen, bis der Nutzer reagiert oder
 * [SuspiciousAppScanController] sie aktiv zurückzieht (eingefroren/deinstalliert/vertraut).
 *
 * `POST_NOTIFICATIONS` (Android 13+) wird best-effort still über [android.app.admin
 * .DevicePolicyManager.setPermissionGrantState] selbst erteilt (s. `WardenApplication`,
 * Device-Owner-Recht) — ohne die Berechtigung zeigt das System die Benachrichtigung laut
 * Android-Dokumentation kommentarlos nicht an (kein Absturz), das try/catch hier ist trotzdem
 * dieselbe defensive Haltung wie überall sonst im Projekt.
 */
class SuspiciousAppNotifier(private val context: Context) {

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sicherheits-Warnungen",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Warnt vor Apps, die im Manifest gefährliche Rechte deklarieren " +
                "(Geräteadministrator, Bedienungshilfen-Dienst)."
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** Fund mit Sofort-Aktionen "Einfrieren"/"Deinstallieren". */
    fun notify(finding: SuspiciousAppFindingInfo) {
        val signalsText = SuspiciousSignal.fromBitmask(finding.signalsBitmask).joinToString(", ") { signal ->
            when (signal) {
                SuspiciousSignal.EXTRA_DEVICE_ADMIN -> "fordert Geräteadministrator-Rechte"
                SuspiciousSignal.ACCESSIBILITY_SERVICE_DECLARED -> "bietet einen Bedienungshilfen-Dienst"
                SuspiciousSignal.OVERLAY_PERMISSION_DECLARED -> "fordert Overlay-Rechte (SYSTEM_ALERT_WINDOW)"
                SuspiciousSignal.NOTIFICATION_LISTENER_DECLARED -> "kann Benachrichtigungen mitlesen"
                SuspiciousSignal.UNKNOWN_INSTALL_SOURCE -> "unbekannte Installationsquelle"
                SuspiciousSignal.SIGNING_CERT_CHANGED -> "Signatur-Zertifikat hat sich geändert"
                SuspiciousSignal.DEVICE_ADMIN_NEWLY_ACTIVATED -> "Geräteadministrator gerade aktiviert"
                SuspiciousSignal.ACCESSIBILITY_SERVICE_NEWLY_ACTIVATED -> "Bedienungshilfen-Dienst gerade aktiviert"
            }
        }
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Sicherheitswarnung")
            .setContentText("Gerät entsperren, um Details und Aktionen zu sehen.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Verdächtige App: ${finding.label}")
            .setContentText(signalsText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${finding.packageName} — $signalsText"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPublicVersion(publicVersion)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .addAction(0, "Einfrieren", actionPendingIntent(SuspiciousAppActionReceiver.ACTION_FREEZE, finding.packageName))
            .addAction(0, "Daten löschen", actionPendingIntent(SuspiciousAppActionReceiver.ACTION_CLEAR_DATA, finding.packageName))
            .addAction(0, "Deinstallieren", actionPendingIntent(SuspiciousAppActionReceiver.ACTION_UNINSTALL, finding.packageName))
        post(finding.packageName, builder)
    }

    /** Ersetzt eine Aktions-Benachrichtigung durch eine reine Fehlermeldung, wenn Einfrieren/
     * Deinstallieren am Android-OS-Schutz für bereits aktive Geräteadmins scheitert (s.
     * [AppUninstaller]-Klassendoc) — ehrlich statt stillschweigend nichts zu tun. Keine
     * Aktionsknöpfe mehr, ein erneuter Versuch schlüge aus demselben Grund wieder fehl. */
    fun showActionFailed(packageName: String, reason: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Aktion fehlgeschlagen: $packageName")
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentText(reason)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(true)
        post(packageName, builder)
    }

    fun cancel(packageName: String) {
        try {
            NotificationManagerCompat.from(context).cancel(packageName, NOTIFICATION_ID)
        } catch (e: SecurityException) {
            Log.w(TAG, "Benachrichtigung konnte nicht zurückgezogen werden: pkg=$packageName", e)
        }
    }

    /** `NotificationManagerCompat.notify()` verlangt laut Lint (`MissingPermission`) einen
     * expliziten try/catch für `POST_NOTIFICATIONS` als "revocable"/gefährliche Berechtigung —
     * ohne sie zeigt Android die Benachrichtigung laut Dokumentation kommentarlos einfach nicht
     * an (kein Absturz), das catch hier ist trotzdem dieselbe defensive Haltung wie überall sonst
     * im Projekt. */
    private fun post(packageName: String, builder: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(context).notify(packageName, NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Benachrichtigung konnte nicht angezeigt werden: pkg=$packageName", e)
        }
    }

    private fun actionPendingIntent(action: String, packageName: String): PendingIntent {
        val intent = Intent(context, SuspiciousAppActionReceiver::class.java).apply {
            this.action = action
            putExtra(SuspiciousAppActionReceiver.EXTRA_PACKAGE_NAME, packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            (action + packageName).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "SuspiciousAppNotifier"
        private const val CHANNEL_ID = "suspicious_app_alerts"
        private const val NOTIFICATION_ID = 1
    }
}
