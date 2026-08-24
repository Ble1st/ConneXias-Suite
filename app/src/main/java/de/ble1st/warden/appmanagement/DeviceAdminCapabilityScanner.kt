package de.ble1st.warden.appmanagement

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Milestone "Manifest-Scan + Sofort-Benachrichtigung" (2026-08-21, auf Nutzerwunsch: "das
 * Manifest von Apps [soll] gescannt werden" statt nur der aktuell *aktive* Zustand) — ersetzt das
 * frühere [android.app.admin.DevicePolicyManager.getActiveAdmins]-basierte Vorgehen
 * (`ActiveDeviceAdminReader`, nur bereits vom Nutzer aktivierte Admins). Liest stattdessen, welche
 * Pakete **überhaupt** einen [DeviceAdminReceiver] im Manifest deklarieren
 * (`PackageManager.queryBroadcastReceivers` für `DEVICE_ADMIN_ENABLED`) — unabhängig davon, ob
 * der Nutzer die Rechte je erteilt hat.
 *
 * Zwei handfeste Gründe für den Wechsel: (1) proaktiv — eine frisch installierte, noch nicht
 * aktivierte Malware-App wird schon *vor* der ersten Rechte-Erteilung erkannt, nicht erst danach.
 * (2) praktisch — live auf dem Testgerät bestätigt (2026-08-21): `setApplicationHidden()`
 * verweigert das Einfrieren/Deinstallieren, sobald das Ziel *bereits aktiver* Geräteadmin ist
 * (Android-OS-Schutz, betrifft auch den Device Owner) — im proaktiven Manifest-Fenster, bevor die
 * Aktivierung stattgefunden hat, funktionieren beide Aktionen dagegen zuverlässig.
 *
 * Kein Sonderrecht über [android.Manifest.permission.QUERY_ALL_PACKAGES] hinaus nötig (bereits
 * vorhanden, s. `InstalledAppLister`-Klassendoc) — dieselbe Begründung greift hier: ohne die
 * Permission würde Android 11+ Package-Visibility die Ergebnisliste sonst auf eine unvollständige
 * Teilmenge filtern.
 */
class DeviceAdminCapabilityScanner(private val context: Context) {

    fun declaredDeviceAdminPackageNames(): Set<String> {
        val intent = Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED)
        val flags = PackageManager.ResolveInfoFlags.of(
            (PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong(),
        )
        return context.packageManager
            .queryBroadcastReceivers(intent, flags)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }
}
