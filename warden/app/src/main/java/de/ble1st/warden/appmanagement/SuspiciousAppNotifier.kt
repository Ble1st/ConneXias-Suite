package de.ble1st.warden.appmanagement

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.ble1st.warden.R
import de.ble1st.warden.domain.appmanagement.SuspiciousSignal
import de.ble1st.warden.domain.appmanagement.ThreatSeverity

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
 *
 * **Drei Kanäle statt einem** (2026-08-25, "Threat Alerts & Severity Levels", Feature-Ideenliste
 * Punkt 0: "Farbcodierung und unterschiedliche Notification-Typen") — je [ThreatSeverity], mit
 * unterschiedlicher `importance` (bestimmt u. a., ob Android überhaupt piept/vibriert) statt eines
 * einzelnen `IMPORTANCE_HIGH`-Kanals für alles. [channelColor] steuert zusätzlich
 * [NotificationCompat.Builder.setColor] — Ampelfarben auf dem kleinen Icon/Akzent, nicht überall
 * vom OEM-Launcher respektiert, aber dieselbe zusätzliche visuelle Unterscheidung wie in
 * Kaspersky-artigen Sicherheits-Apps aus der Feature-Ideenliste. Ein bereits erstellter Kanal
 * ändert seine `importance` durch einen erneuten [NotificationChannel]-Konstruktoraufruf laut
 * Android-Doku *nicht* mehr — unkritisch hier, die drei IDs sind seit dieser Runde stabil, es gab
 * vorher nur den einen `CHANNEL_ID`-Kanal (Alt-Installationen behalten dessen Historie, bekommen
 * die drei neuen Kanäle aber ab dem nächsten Start zusätzlich).
 */
class SuspiciousAppNotifier(private val context: Context) {

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        for (severity in ThreatSeverity.entries) {
            val channel = NotificationChannel(
                channelId(severity),
                context.getString(R.string.notification_suspicious_app_channel_name, severityLabel(severity)),
                channelImportance(severity),
            ).apply {
                description = context.getString(R.string.notification_suspicious_app_channel_description, severityLabel(severity))
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            manager?.createNotificationChannel(channel)
        }
    }

    /** Fund mit Sofort-Aktionen "Einfrieren"/"Deinstallieren". */
    fun notify(finding: SuspiciousAppFindingInfo) {
        val severity = finding.severity
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
                SuspiciousSignal.VERSION_DOWNGRADED -> "auf eine ältere Version zurückgestuft"
            }
        }
        val channelId = channelId(severity)
        val publicVersion = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_suspicious_app_public_title, severityLabel(severity)))
            .setContentText(context.getString(R.string.notification_suspicious_app_public_text))
            .setPriority(notificationPriority(severity))
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_suspicious_app_title, severityLabel(severity), finding.label))
            .setContentText(signalsText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${finding.packageName} — $signalsText"))
            .setPriority(notificationPriority(severity))
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setColor(channelColor(severity))
            .setColorized(severity == ThreatSeverity.CRITICAL)
            .setPublicVersion(publicVersion)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .addAction(0, context.getString(R.string.notification_suspicious_app_action_freeze), confirmActionPendingIntent(SuspiciousAppActionConfirmActivity.ACTION_FREEZE, finding.packageName))
            .addAction(0, context.getString(R.string.notification_suspicious_app_action_clear_data), confirmActionPendingIntent(SuspiciousAppActionConfirmActivity.ACTION_CLEAR_DATA, finding.packageName))
            .addAction(0, context.getString(R.string.notification_suspicious_app_action_uninstall), confirmActionPendingIntent(SuspiciousAppActionConfirmActivity.ACTION_UNINSTALL, finding.packageName))
        post(finding.packageName, builder)
    }

    /** Ersetzt eine Aktions-Benachrichtigung durch eine reine Fehlermeldung, wenn Einfrieren/
     * Deinstallieren am Android-OS-Schutz für bereits aktive Geräteadmins scheitert (s.
     * [AppUninstaller]-Klassendoc) — ehrlich statt stillschweigend nichts zu tun. Keine
     * Aktionsknöpfe mehr, ein erneuter Versuch schlüge aus demselben Grund wieder fehl.
     * Immer über den [ThreatSeverity.WARNING]-Kanal — es gibt hier keinen Fund/keine Signale, aus
     * denen sich eine Stufe ableiten ließe, aber "eine Aktion ist fehlgeschlagen" ist mehr als
     * reine Information. */
    fun showActionFailed(packageName: String, reason: String) {
        val builder = NotificationCompat.Builder(context, channelId(ThreatSeverity.WARNING))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notification_suspicious_app_action_failed_title, packageName))
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

    /**
     * "Einfrieren"/"Deinstallieren"/"Daten löschen" (2026-08-28, Befund S-5; "Einfrieren" seit
     * analyse.md 2026-09-02, Befund Hoch dazugekommen): `getActivity()` statt `getBroadcast()` —
     * alle drei Aktionen führen jetzt zuerst auf [SuspiciousAppActionConfirmActivity]s
     * WardenLock-Nachweis + Bestätigungsdialog, nicht mehr direkt auf einen ausführenden Receiver.
     * `FLAG_ACTIVITY_NEW_TASK` ist Pflicht: der Start kommt aus einem Notification-Kontext, keine
     * bestehende Activity, von der aus gestartet werden könnte.
     */
    private fun confirmActionPendingIntent(action: String, packageName: String): PendingIntent {
        val intent = Intent(context, SuspiciousAppActionConfirmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SuspiciousAppActionConfirmActivity.EXTRA_ACTION, action)
            putExtra(SuspiciousAppActionReceiver.EXTRA_PACKAGE_NAME, packageName)
        }
        return PendingIntent.getActivity(
            context,
            (action + packageName).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun channelId(severity: ThreatSeverity): String = when (severity) {
        ThreatSeverity.INFO -> "suspicious_app_alerts_info"
        ThreatSeverity.WARNING -> "suspicious_app_alerts_warning"
        ThreatSeverity.CRITICAL -> "suspicious_app_alerts_critical"
    }

    private fun severityLabel(severity: ThreatSeverity): String = when (severity) {
        ThreatSeverity.INFO -> "Info"
        ThreatSeverity.WARNING -> "Warnung"
        ThreatSeverity.CRITICAL -> "Kritisch"
    }

    /** `IMPORTANCE_LOW` (Info) postet lautlos, `IMPORTANCE_DEFAULT` (Warnung) piept einmal,
     * `IMPORTANCE_HIGH` (Kritisch) erscheint als Heads-up — je dringlicher die Stufe, desto eher
     * soll die Benachrichtigung sofort auffallen statt nur in der Leiste zu liegen. */
    private fun channelImportance(severity: ThreatSeverity): Int = when (severity) {
        ThreatSeverity.INFO -> NotificationManager.IMPORTANCE_LOW
        ThreatSeverity.WARNING -> NotificationManager.IMPORTANCE_DEFAULT
        ThreatSeverity.CRITICAL -> NotificationManager.IMPORTANCE_HIGH
    }

    private fun notificationPriority(severity: ThreatSeverity): Int = when (severity) {
        ThreatSeverity.INFO -> NotificationCompat.PRIORITY_LOW
        ThreatSeverity.WARNING -> NotificationCompat.PRIORITY_DEFAULT
        ThreatSeverity.CRITICAL -> NotificationCompat.PRIORITY_HIGH
    }

    /** Ampelfarben — dieselbe Blau/Orange/Rot-Konvention wie
     * [de.ble1st.warden.ui.SecurityScannerScreen]s Farbcodierung der Funde-Liste, ein einziger
     * Ort für beide (s. dortiges `severityColor`). */
    private fun channelColor(severity: ThreatSeverity): Int = when (severity) {
        ThreatSeverity.INFO -> Color.parseColor("#1565C0")
        ThreatSeverity.WARNING -> Color.parseColor("#E65100")
        ThreatSeverity.CRITICAL -> Color.parseColor("#B00020")
    }

    companion object {
        private const val TAG = "SuspiciousAppNotifier"
        private const val NOTIFICATION_ID = 1
    }
}
