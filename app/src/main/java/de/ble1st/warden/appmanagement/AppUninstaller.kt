package de.ble1st.warden.appmanagement

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Milestone "Manifest-Scan + Sofort-Benachrichtigung" (2026-08-21, auf Nutzerwunsch: "Option, die
 * App sofort zu freezen/deinstallieren") — Wardens erste Deinstallations-Aktion; bislang gab es
 * nur Einfrieren ([AppManagementController]). Für den Device Owner läuft
 * [android.content.pm.PackageInstaller.uninstall] still, ohne System-Bestätigungsdialog — anders
 * als [de.ble1st.warden.appmanagement.AppFreezeManager.setFrozen] aber **asynchron**: das
 * Ergebnis kommt nicht als Rückgabewert dieser Methode, sondern später über
 * [SuspiciousAppActionReceiver]s `ACTION_UNINSTALL_RESULT`-Zweig zurück (derselbe
 * `IntentSender`-Callback-Mechanismus, den `PackageInstaller` grundsätzlich verlangt).
 *
 * Denselben, live bestätigten Android-OS-Schutz wie beim Einfrieren (2026-08-21, s.
 * [DeviceAdminCapabilityScanner]-Klassendoc) gibt es auch hier: ein Ziel, das *bereits aktiver*
 * Geräteadministrator ist, lässt sich nicht deinstallieren (`DELETE_FAILED_DEVICE_POLICY_MANAGER`)
 * — der Aufrufer bekommt das über den Ergebnis-Callback ehrlich mitgeteilt, statt stillschweigend
 * nichts zu tun.
 */
class AppUninstaller(private val context: Context) {

    fun uninstall(packageName: String) {
        val resultIntent = Intent(context, SuspiciousAppActionReceiver::class.java).apply {
            action = SuspiciousAppActionReceiver.ACTION_UNINSTALL_RESULT
            putExtra(SuspiciousAppActionReceiver.EXTRA_PACKAGE_NAME, packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            packageName.hashCode(),
            resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        try {
            context.packageManager.packageInstaller.uninstall(packageName, pendingIntent.intentSender)
        } catch (e: SecurityException) {
            Log.w(TAG, "uninstall blocked: pkg=$packageName", e)
            resultIntent.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            context.sendBroadcast(resultIntent)
        }
    }

    private companion object {
        const val TAG = "AppUninstaller"
    }
}
