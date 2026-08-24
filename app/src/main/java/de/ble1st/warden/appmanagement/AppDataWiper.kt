package de.ble1st.warden.appmanagement

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import de.ble1st.warden.admin.WardenDeviceAdminReceiver

/**
 * Tier 3 ("App-Kontrolle", 2026-08-22) — "leichte" destruktive Aktion für eine einzelne
 * verdächtige App: löscht nur deren Daten (`DevicePolicyManager.clearApplicationUserData`),
 * ohne sie wie [AppUninstaller] komplett zu entfernen. Sinnvoller Zwischenschritt für den
 * Verdachtsscanner, wenn Einfrieren/Deinstallieren an der in CLAUDE.md dokumentierten
 * Geräteadmin-Einschränkung scheitern (s. [AppFreezeManager]-Klassendoc) — Datenlöschung ist
 * davon unabhängig, betrifft keinen Geräteadmin-Aktivierungszustand.
 *
 * Anders als [AppUninstaller] **kein** `PendingIntent`/`IntentSender`-Umweg nötig: die DPM-API
 * nimmt hier direkt einen `Executor` + Callback entgegen, kein `PackageInstaller`-Mechanismus.
 *
 * **`SecurityException` abgefangen (Review-Fund 2026-08-24):** derselbe Grundsatz wie bei
 * [AppUninstaller.uninstall] — ein verlorener Device-Owner-Status zwischen Scan und Tap auf
 * "Daten löschen" darf den aufrufenden [de.ble1st.warden.appmanagement.SuspiciousAppActionReceiver]
 * nicht abstürzen lassen, sondern muss als `onResult(false)` ankommen, damit die dafür
 * vorgesehene "Datenlöschung fehlgeschlagen"-Meldung greift (s.
 * `SuspiciousAppScanController.handleClearDataAction`).
 */
class AppDataWiper(private val context: Context) {

    private val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)

    private fun devicePolicyManager(): DevicePolicyManager =
        checkNotNull(context.getSystemService(DevicePolicyManager::class.java)) {
            "DevicePolicyManager nicht verfügbar"
        }

    fun clear(packageName: String, onResult: (success: Boolean) -> Unit) {
        try {
            devicePolicyManager().clearApplicationUserData(
                admin,
                packageName,
                ContextCompat.getMainExecutor(context),
            ) { _, succeeded -> onResult(succeeded) }
        } catch (e: SecurityException) {
            onResult(false)
        }
    }
}
