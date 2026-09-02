package de.ble1st.warden.appmanagement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import de.ble1st.warden.WardenApplication

/**
 * Milestone "Manifest-Scan + Sofort-Benachrichtigung" (2026-08-21) — dünner Empfänger für den
 * asynchronen Deinstallations-Ergebnis-Callback aus [AppUninstaller] (derselbe `IntentSender`-
 * Mechanismus, den `PackageInstaller.uninstall` verlangt — das Ergebnis kommt als eigener
 * Broadcast, nicht synchron zurück). Die eigentliche Logik lebt in [SuspiciousAppScanController]
 * (dasselbe "dünner Receiver, Logik im Controller"-Muster wie [de.ble1st.warden.boot
 * .RegistryReconciliationReceiver]).
 *
 * **"Deinstallieren"/"Daten löschen" laufen seit 2026-08-28, "Einfrieren" seit analyse.md
 * (2026-09-02, Befund Hoch) nicht mehr über diesen Receiver.** Ein Broadcast aus der
 * Benachrichtigungsschublade führte alle drei an [de.ble1st.warden.presence.WardenLockActivity]
 * komplett vorbei aus, ein einziger Tap auf ein entsperrtes Gerät genügte — bei "Einfrieren" lange
 * bewusst so belassen ("reversibel, dieselbe Einstufung wie ein Safeguard-Schalter"), bis
 * analyse.md darauf hinwies, dass ein gewöhnlicher Safeguard-Schalter seit `WardenLockActivity`
 * selbst nicht mehr ohne diesen Nachweis erreichbar ist. Alle drei führen jetzt über
 * [SuspiciousAppActionConfirmActivity] (dortiges Klassendoc), die zuerst denselben
 * App-Eintritts-Nachweis verlangt und dann einen expliziten Bestätigungsschritt zeigt, bevor sie
 * [SuspiciousAppScanController.handleFreezeAction]/`handleUninstallAction`/`handleClearDataAction`
 * selbst aufruft — nicht mehr über diesen Receiver. Dieser Receiver bleibt ausschließlich für den
 * `ACTION_UNINSTALL_RESULT`-Callback bestehen.
 *
 * `exported="false"` — nur Androids `PackageInstaller`-Ergebnis-Callback (an dieselbe App
 * gerichtet) löst diesen Receiver aus, keine fremde App kann ihn ansprechen.
 */
class SuspiciousAppActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        val controller = (context.applicationContext as WardenApplication).suspiciousAppScanController
        if (intent.action == ACTION_UNINSTALL_RESULT) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            controller.handleUninstallResult(packageName, status == PackageInstaller.STATUS_SUCCESS)
        }
    }

    companion object {
        const val ACTION_UNINSTALL_RESULT = "de.ble1st.warden.action.SUSPICIOUS_APP_UNINSTALL_RESULT"
        const val EXTRA_PACKAGE_NAME = "de.ble1st.warden.extra.PACKAGE_NAME"
    }
}
