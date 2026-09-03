package de.ble1st.warden.appmanagement

import android.content.Context
import android.content.pm.PackageManager

/**
 * "Permission-Diff bei App-Updates" (2026-09-03) — liest die aktuell deklarierten *gefährlichen*
 * Permissions eines einzelnen Pakets für [de.ble1st.warden.domain.appmanagement
 * .PermissionEscalationDecision], dasselbe schmale "nur die eine Android-API kapseln"-Muster wie
 * [PackageVersionReader]/[SigningCertReader]. Nutzt [PermissionClassifier] — dieselbe
 * Klassifizierung wie [PermissionAuditScanner], hier aber pro Einzelpaket statt für die gesamte
 * Geräteliste (der Verdachtsscanner iteriert ohnehin schon paketweise, s.
 * [SuspiciousAppScanController.prepareScan]).
 *
 * Leere Menge, wenn das Paket nicht (mehr) auflösbar ist oder keine gefährlichen Permissions
 * deklariert — beides sind gültige, nicht fehlerhafte Zustände (anders als [PackageVersionReader
 * .versionCodeFor], wo `null` "keine Baseline" bedeutet: hier ist "keine gefährlichen Rechte" ein
 * völlig normaler Fall für die meisten Apps).
 */
class DangerousPermissionReader(private val context: Context) {

    fun dangerousPermissionsFor(packageName: String): Set<String> {
        val requested = runCatching {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            ).requestedPermissions?.toList().orEmpty()
        }.getOrDefault(emptyList())

        return requested
            .filterTo(mutableSetOf()) { PermissionClassifier.classify(context, it) == PermissionCategory.DANGEROUS }
    }
}
