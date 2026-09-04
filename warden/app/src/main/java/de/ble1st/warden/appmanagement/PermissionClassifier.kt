package de.ble1st.warden.appmanagement

import android.Manifest
import android.content.Context
import android.content.pm.PermissionInfo

/** "Detaillierte Permission-Audit-Reports" (Feature-Ideenliste Punkt 22: "Klassifizierung von
 * Permissions (Normal, Dangerous, Special)"). [SPECIAL] deckt die AppOps-vermittelten
 * "besonderen Zugriffsrechte" ab, die Android **nicht** über das normale
 * `PermissionInfo.protectionLevel`-Laufzeit-Schema modelliert (kein Laufzeit-Dialog, stattdessen
 * ein eigener Einstellungen-Bildschirm oder eine Device-Owner-Policy) — dieselbe Kategorie, die
 * dieses Projekt an anderer Stelle bereits einzeln kennt: [OverlayPermissionScanner]
 * (`SYSTEM_ALERT_WINDOW`), [NotificationListenerScanner] (`BIND_NOTIFICATION_LISTENER_SERVICE`).
 * [UNKNOWN], wenn `PackageManager.getPermissionInfo` das Recht nicht kennt (z. B. eine
 * App-eigene, custom-definierte Permission eines anderen Pakets, die selbst gerade nicht
 * installiert oder deren Definition inzwischen entfernt ist). */
enum class PermissionCategory { NORMAL, DANGEROUS, SIGNATURE, SPECIAL, UNKNOWN }

/**
 * "LockMode/Threat-Protection-Ausbau" (2026-08-25, Feature-Ideenliste Punkt 22: "Für Warden:
 * `PermissionChecker` + Manifest-Parsing"). Nutzt tatsächlich `PackageManager.getPermissionInfo`
 * statt `PermissionChecker` — `PermissionChecker` beantwortet nur "hat *diese* App gerade Zugriff
 * auf *eine* Permission" (für den eigenen Prozess gedacht), hier geht es aber um die *Klassifizierung*
 * beliebiger, im Manifest eines *fremden* Pakets deklarierter Rechte, unabhängig vom aktuellen
 * Gewährungsstatus — dafür ist `PermissionInfo.protectionLevel` die richtige API.
 */
object PermissionClassifier {

    /** AppOps-/Special-Access-Rechte ohne klassisches `protectionLevel`-Dangerous-Flag, aber mit
     * vergleichbarer oder höherer Tragweite — dieselbe Handvoll, die dieser Scanner bereits über
     * eigene, ältere Signale kennt ([SuspiciousSignal.OVERLAY_PERMISSION_DECLARED]/
     * [SuspiciousSignal.NOTIFICATION_LISTENER_DECLARED]), hier zu einer generischen Klassifizierung
     * erweitert statt weiterer Einzel-Scanner. */
    private val SPECIAL_PERMISSIONS = setOf(
        Manifest.permission.SYSTEM_ALERT_WINDOW,
        Manifest.permission.WRITE_SETTINGS,
        Manifest.permission.PACKAGE_USAGE_STATS,
        Manifest.permission.REQUEST_INSTALL_PACKAGES,
        Manifest.permission.BIND_ACCESSIBILITY_SERVICE,
        Manifest.permission.BIND_DEVICE_ADMIN,
        Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE,
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.SCHEDULE_EXACT_ALARM",
    )

    fun classify(context: Context, permissionName: String): PermissionCategory {
        if (permissionName in SPECIAL_PERMISSIONS) return PermissionCategory.SPECIAL
        val info = runCatching {
            context.packageManager.getPermissionInfo(permissionName, 0)
        }.getOrNull() ?: return PermissionCategory.UNKNOWN
        return when (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) {
            PermissionInfo.PROTECTION_DANGEROUS -> PermissionCategory.DANGEROUS
            PermissionInfo.PROTECTION_SIGNATURE -> PermissionCategory.SIGNATURE
            PermissionInfo.PROTECTION_NORMAL -> PermissionCategory.NORMAL
            else -> PermissionCategory.UNKNOWN
        }
    }
}
