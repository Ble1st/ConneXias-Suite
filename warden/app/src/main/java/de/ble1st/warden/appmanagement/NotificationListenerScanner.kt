package de.ble1st.warden.appmanagement

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService

/**
 * Milestone "weitere Funktionen für den Sicherheitsscanner" (2026-08-22, Feature 2) — liest,
 * welche Pakete einen [NotificationListenerService] im Manifest deklarieren
 * (`queryIntentServices` für [NotificationListenerService.SERVICE_INTERFACE]), dieselbe
 * "Fähigkeit statt Aktivierung"-Bauweise wie [DeviceAdminCapabilityScanner]. Ein aktivierter
 * Notification-Listener kann Benachrichtigungsinhalte mitlesen — für 2FA-/Einmalcode-Apps ein
 * bekannter, oft übersehener Angriffsvektor (der Zugriff wird über einen separaten
 * "Benachrichtigungszugriff"-Bildschirm in den Systemeinstellungen erteilt, nicht über den
 * normalen Runtime-Permission-Dialog, deshalb leicht zu übersehen).
 */
class NotificationListenerScanner(private val context: Context) {

    fun declaredNotificationListenerPackageNames(): Set<String> {
        val intent = Intent(NotificationListenerService.SERVICE_INTERFACE)
        val flags = PackageManager.ResolveInfoFlags.of(
            (PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong(),
        )
        return context.packageManager
            .queryIntentServices(intent, flags)
            .mapNotNull { it.serviceInfo?.packageName }
            .toSet()
    }
}
