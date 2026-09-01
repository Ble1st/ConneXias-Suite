package de.ble1st.warden.appmanagement

import android.content.Context
import android.content.pm.PackageManager

/**
 * "Sentinel: eigenständige Kiosk-PIN-App" (2026-08-26) — liest Sentinels aktuellen
 * Installationsstatus live über [PackageManager] (dieselbe "Wahrheit im System, keine eigene
 * Speicherung"-Haltung wie bei [de.ble1st.warden.domain.registry.Safeguard.isActive], s.
 * [SentinelInstallResultReceiver]-Klassendoc), für die "Sentinel installieren/aktualisieren"-Zeile
 * in `SafeguardsScreen.kt`.
 */
class SentinelInstallStatusReader(private val context: Context) {

    fun currentStatus(): SentinelInstallStatus {
        val info = runCatching {
            context.packageManager.getPackageInfo(SentinelSilentInstaller.SENTINEL_PACKAGE_NAME, PackageManager.PackageInfoFlags.of(0))
        }.getOrNull() ?: return SentinelInstallStatus.NotInstalled
        return SentinelInstallStatus.Installed(versionName = info.versionName, versionCode = info.longVersionCode)
    }
}

sealed class SentinelInstallStatus {
    data object NotInstalled : SentinelInstallStatus()
    data class Installed(val versionName: String?, val versionCode: Long) : SentinelInstallStatus()
}
