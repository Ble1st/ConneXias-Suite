package de.ble1st.warden.appmanagement

import android.content.Context
import android.content.pm.PackageManager
import de.ble1st.warden.domain.appmanagement.PermissionAuditDecision

/**
 * "Detaillierte Permission-Audit-Reports" (2026-08-25, Feature-Ideenliste Punkt 22). Iteriert
 * [InstalledAppLister]s vollständige Paketliste (dieselbe [android.Manifest.permission
 * .QUERY_ALL_PACKAGES]-Sichtbarkeit wie der Verdachtsscanner) und klassifiziert je Paket dessen
 * deklarierte Rechte über [PermissionClassifier].
 *
 * **Kann mehrere hundert `PackageManager`-Aufrufe auslösen** (ein `getPackageInfo` pro Paket, ein
 * `getPermissionInfo` pro darin deklariertem Recht) — Aufrufer müssen das auf `Dispatchers.IO`
 * ausführen, dieselbe Erwartung wie bei [InstalledAppLister.listInstalledApps]/
 * [SuspiciousAppScanController.scan] (s. jeweiliges Klassendoc/UI-Aufrufer).
 *
 * `includeSystemApps = false` per Default — dasselbe "Bedrohungsmodell betrifft nachträglich
 * installierte Fremd-Apps"-Argument wie [SuspiciousAppScanDecision]s Systemapp-Ausschluss:
 * vorinstallierte Systemapps deklarieren routinemäßig viele Signature-/Special-Rechte, die für
 * dieses Audit kein aussagekräftiges Signal wären.
 */
class PermissionAuditScanner(private val context: Context) {

    fun scan(includeSystemApps: Boolean = false): List<PermissionAuditInfo> {
        val appLister = InstalledAppLister(context)
        return appLister.listInstalledApps()
            .filter { includeSystemApps || !it.isSystemApp }
            .map { entry -> auditFor(entry) }
            .sortedByDescending { it.dangerousPermissions.size }
    }

    private fun auditFor(entry: InstalledAppEntry): PermissionAuditInfo {
        val requested = runCatching {
            context.packageManager.getPackageInfo(
                entry.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            ).requestedPermissions?.toList().orEmpty()
        }.getOrDefault(emptyList())

        val dangerous = mutableListOf<String>()
        val special = mutableListOf<String>()
        for (permission in requested) {
            when (PermissionClassifier.classify(context, permission)) {
                PermissionCategory.DANGEROUS -> dangerous += permission
                PermissionCategory.SPECIAL -> special += permission
                PermissionCategory.NORMAL, PermissionCategory.SIGNATURE, PermissionCategory.UNKNOWN -> {}
            }
        }

        return PermissionAuditInfo(
            packageName = entry.packageName,
            label = entry.label,
            isSystemApp = entry.isSystemApp,
            dangerousPermissions = dangerous,
            specialPermissions = special,
            tooManyDangerousPermissions = PermissionAuditDecision.tooManyDangerousPermissions(dangerous.size),
        )
    }
}
