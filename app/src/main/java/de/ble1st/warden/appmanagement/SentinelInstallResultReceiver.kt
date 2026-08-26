package de.ble1st.warden.appmanagement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import de.ble1st.warden.domain.registry.SafeguardRegistry
import de.ble1st.warden.logging.HashChainLogStore
import de.ble1st.warden.registry.PersistentSafeguardRegistry
import de.ble1st.warden.registry.RegistryStorage
import de.ble1st.warden.registry.SafeguardCatalog
import de.ble1st.warden.registry.SafeguardRegistryStore
import de.ble1st.warden.registry.SentinelUninstallProtectionSafeguard
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
 *
 * **Live-Drill-Folgearbeit (2026-08-26, auf ausdrücklichen Nutzerwunsch "von Anfang an aktiv, muss
 * im Safeguard explizit deaktiviert werden"):** bei `STATUS_SUCCESS` wird
 * [SentinelUninstallProtectionSafeguard] hier sofort scharf geschaltet — kein manuelles
 * Umschalten in den Safeguards nötig, der Deinstallations-Schutz greift damit vom allerersten
 * Moment an, in dem Sentinel überhaupt existiert. Bleibt trotzdem ein regulärer, in
 * [SafeguardCatalog.reversible] registrierter Safeguard (Boot-Reconciliation deckt auch eine
 * *bereits* installierte Sentinel-Kopie aus einer älteren Warden-Version ohne diesen Aufruf hier
 * ab) — das Ausschalten bleibt bewusst ein expliziter Schritt in [de.ble1st.warden.ui
 * .SafeguardsScreen] oder über `MASTER_SWITCH_REVERT`, nie automatisch.
 */
class SentinelInstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val logStore = wardenAuditLog(context)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            logStore.append(Log.WARN, TAG, "Sentinel-Silent-Install erfolgreich")
            armUninstallProtection(context, logStore)
        } else {
            logStore.append(Log.ERROR, TAG, "Sentinel-Silent-Install fehlgeschlagen: status=$status message=$message")
        }
    }

    private fun armUninstallProtection(context: Context, logStore: HashChainLogStore) {
        val registry = PersistentSafeguardRegistry(
            SafeguardRegistry(),
            SafeguardRegistryStore(RegistryStorage.buildEnvelopeFile(context)),
        )
        SafeguardCatalog.registerReversible(registry, context)
        registry.load()
        val applied = runCatching { registry.apply(SentinelUninstallProtectionSafeguard.ID) }.isSuccess
        logStore.append(
            if (applied) Log.WARN else Log.ERROR,
            TAG,
            "Sentinel-Deinstallationsschutz automatisch scharf geschaltet: success=$applied",
        )
    }

    private companion object {
        const val TAG = "SentinelInstallResult"
    }
}
