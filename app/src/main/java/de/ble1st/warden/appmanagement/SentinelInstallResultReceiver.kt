package de.ble1st.warden.appmanagement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import de.ble1st.warden.wardenAuditLog

/**
 * "Sentinel: eigenständige Kiosk-PIN-App" (2026-08-26) — asynchroner
 * `PackageInstaller.Session.commit()`-Ergebnis-Callback für [SentinelSilentInstaller], derselbe
 * `IntentSender`-Mechanismus, den [AppUninstaller]/[SuspiciousAppActionReceiver] bereits für die
 * Deinstallations-Aktion nutzen. Rein protokollierend — der aktuelle Installationsstatus selbst
 * wird nicht separat gespeichert, sondern von der UI live über `PackageManager.getPackageInfo`
 * abgefragt (dieselbe "Wahrheit im System, keine eigene Speicherung"-Haltung wie bei
 * [de.ble1st.warden.domain.registry.Safeguard.isActive]) — ein verlorener Log-Eintrag hätte also
 * keine funktionale Konsequenz, nur eine fehlende Diagnose-Zeile.
 *
 * `exported="false"` — nur Androids an dieselbe App gerichteter `PackageInstaller`-Ergebnis-
 * Callback löst diesen Receiver aus, keine fremde App kann ihn ansprechen.
 */
class SentinelInstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val logStore = wardenAuditLog(context)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            logStore.append(Log.WARN, TAG, "Sentinel-Silent-Install erfolgreich")
        } else {
            logStore.append(Log.ERROR, TAG, "Sentinel-Silent-Install fehlgeschlagen: status=$status message=$message")
        }
    }

    private companion object {
        const val TAG = "SentinelInstallResult"
    }
}
