package de.ble1st.warden.appmanagement

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * Milestone "weitere Funktionen für den Sicherheitsscanner" (2026-08-22, Feature 1) — liest,
 * welche Pakete [Manifest.permission.SYSTEM_ALERT_WINDOW] anfordern. Bekannte Missbrauchsmasche
 * (Overlay-/Tapjacking-Trojaner, dieselbe Kategorie wie [AccessibilityServiceScanner]s
 * Klassendoc beschreibt): ein Overlay kann echte UI verdecken oder imitieren, um z. B.
 * Zugangsdaten abzugreifen oder Berechtigungsdialoge zu verschleiern.
 *
 * `getPackagesHoldingPermissions` statt eines Manifest-Scans wie bei
 * [DeviceAdminCapabilityScanner] — funktional äquivalent (liefert ebenfalls Pakete, die die
 * Berechtigung nur *deklarieren*, unabhängig vom Laufzeit-Gewährungsstatus, den
 * `SYSTEM_ALERT_WINDOW` als "special access"-Berechtigung ohnehin nicht über den normalen
 * Runtime-Permission-Dialog erhält), aber die direktere API für "wer fordert Permission X an".
 */
class OverlayPermissionScanner(private val context: Context) {

    fun declaredOverlayPermissionPackageNames(): Set<String> {
        val flags = PackageManager.PackageInfoFlags.of(
            (PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong(),
        )
        return context.packageManager
            .getPackagesHoldingPermissions(arrayOf(Manifest.permission.SYSTEM_ALERT_WINDOW), flags)
            .map { it.packageName }
            .toSet()
    }
}
