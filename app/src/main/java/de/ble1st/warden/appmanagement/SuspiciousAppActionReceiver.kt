package de.ble1st.warden.appmanagement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import de.ble1st.warden.WardenApplication

/**
 * Milestone "Manifest-Scan + Sofort-Benachrichtigung" (2026-08-21) — dünner Empfänger für die
 * Sofort-Aktionen aus [SuspiciousAppNotifier]s Benachrichtigung ("Einfrieren"/"Deinstallieren")
 * sowie den asynchronen Deinstallations-Ergebnis-Callback aus [AppUninstaller] (derselbe
 * `IntentSender`-Mechanismus, den `PackageInstaller.uninstall` verlangt — das Ergebnis kommt als
 * eigener Broadcast, nicht synchron zurück). Die eigentliche Logik lebt in
 * [SuspiciousAppScanController] (dasselbe "dünner Receiver, Logik im Controller"-Muster wie
 * [de.ble1st.warden.boot.RegistryReconciliationReceiver]).
 *
 * `exported="false"` — nur Wardens eigene [SuspiciousAppNotifier]-PendingIntents und Androids
 * `PackageInstaller`-Ergebnis-Callback (an dieselbe App gerichtet) lösen diesen Receiver aus,
 * keine fremde App kann ihn ansprechen.
 */
class SuspiciousAppActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        val controller = (context.applicationContext as WardenApplication).suspiciousAppScanController
        when (intent.action) {
            ACTION_FREEZE -> controller.handleFreezeAction(packageName)
            ACTION_UNINSTALL -> controller.handleUninstallAction(packageName)
            ACTION_CLEAR_DATA -> controller.handleClearDataAction(packageName)
            ACTION_UNINSTALL_RESULT -> {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                controller.handleUninstallResult(packageName, status == PackageInstaller.STATUS_SUCCESS)
            }
        }
    }

    companion object {
        const val ACTION_FREEZE = "de.ble1st.warden.action.SUSPICIOUS_APP_FREEZE"
        const val ACTION_UNINSTALL = "de.ble1st.warden.action.SUSPICIOUS_APP_UNINSTALL"
        const val ACTION_CLEAR_DATA = "de.ble1st.warden.action.SUSPICIOUS_APP_CLEAR_DATA"
        const val ACTION_UNINSTALL_RESULT = "de.ble1st.warden.action.SUSPICIOUS_APP_UNINSTALL_RESULT"
        const val EXTRA_PACKAGE_NAME = "de.ble1st.warden.extra.PACKAGE_NAME"
    }
}
