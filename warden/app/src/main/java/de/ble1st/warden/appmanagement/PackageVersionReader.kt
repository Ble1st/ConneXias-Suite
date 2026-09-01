package de.ble1st.warden.appmanagement

import android.content.Context
import android.content.pm.PackageManager

/**
 * "LockMode/Threat-Protection-Ausbau" (2026-08-25) — liest den `versionCode` eines Pakets für
 * [de.ble1st.warden.domain.appmanagement.VersionDowngradeDecision], dasselbe schmale
 * "nur die eine Android-API kapseln"-Muster wie [SigningCertReader].
 *
 * `null`, wenn das Paket nicht (mehr) auflösbar ist — der Aufrufer behandelt das wie "keine
 * Baseline für dieses Paket", nicht wie ein Fehler (dieselbe Haltung wie
 * [SigningCertReader.fingerprintFor]).
 */
class PackageVersionReader(private val context: Context) {

    fun versionCodeFor(packageName: String): Long? = runCatching {
        context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
    }.getOrNull()
}
