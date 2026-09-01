// ⏸ PAUSIERT (2026-08-27): "Netz-Sperre" ist vorübergehend deaktiviert — Live-Test auf dem
// physischen Testgerät fand nach mehreren echten Bugfixes (siehe Commit 7252396 und
// warden-netzsperre-feature-2026-08-27-Memo) einen weiterhin ungeklärten Kernfehler: die
// DNS-Blockliste/NAT-Relay verarbeitet auf einem frisch aufgebauten Tunnel keinen Traffic mehr,
// Ursache unbekannt. Diese Datei liegt deshalb bewusst außerhalb jedes Gradle-Source-Sets
// (app/netlock-disabled/ statt app/src/main/java/) — wird NICHT mitkompiliert, ist nirgendwo
// verkabelt. Zum Reaktivieren: Verzeichnis zurück nach app/src/main/java/... verschieben, alle
// Wiederverkabelungsstellen aus dem Deaktivierungs-Commit rückgängig machen (siehe dessen
// Commit-Message für die vollständige Liste), Kernfehler zuerst klären.

package de.ble1st.warden.netlock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import de.ble1st.warden.domain.netlock.NetLockdownReconcileDecision
import de.ble1st.warden.logging.HashChainLogStore
import de.ble1st.warden.logging.LogStorage

/**
 * "Netz-Sperre" (2026-08-27): Warden-seitige Source of Truth für "Net-Lockdown scharf?" —
 * portiert vom ConneXias-Framework-Quellprojekt (`warden-app/vnp/NetLockdownController.kt`), dort
 * gegen ein fremdes Barbican-APK, hier gegen [WardenVpnService] im selben Prozess (kein
 * Cross-APK-`startService`-Aufruf mehr nötig — Android startet Wardens eigenen VPN-Service über
 * `setAlwaysOnVpnPackage` ohnehin selbst, s. [NetLockdownAuthorizer]-Klassendoc). Arm/disarm rührt
 * DPMs Always-On-VPN an, persistiert den Soll-Zustand für die Boot-Reconciliation. [disarm] räumt
 * DPM immer auf, auch wenn [WardenVpnService] gerade tot ist (Invariante 1b aus dem
 * Quellprojekt-Fund).
 */
class NetLockdownController(context: Context) {

    private val appContext = context.applicationContext
    private val authorizer = NetLockdownAuthorizer(appContext)
    private val store = NetLockdownStore(NetLockdownStore.buildEnvelopeFile(appContext))
    private val logStore = HashChainLogStore(LogStorage.buildEnvelopeFile(appContext))

    fun isActive(): Boolean = authorizer.isActive()

    fun currentLockdownAllowlist(): Set<String>? = authorizer.currentLockdownAllowlist()

    fun desiredArmed(): Boolean? = store.loadDesiredArmed()

    sealed class ArmResult {
        data object Success : ArmResult()
        data class Failed(val reason: String) : ArmResult()
    }

    fun arm(): ArmResult = try {
        val allowedPackages = currentFirewallAllowedPackages()
        authorizer.apply(allowedPackages)
        // Der Always-On-VPN-Service wird nach setAlwaysOnVpnPackage vom System gestartet — Warden
        // muss WardenVpnService nicht selbst starten (dasselbe Prinzip wie im Quellprojekt, dort
        // wegen exported=false zwingend, hier zusätzlich einfach unnötig).
        requestBatteryOptimizationExemptionOnce()
        store.saveDesiredArmed(true)
        logStore.append(Log.WARN, TAG, "Net-Lockdown scharf (Always-On-VPN + Lockdown)")
        ArmResult.Success
    } catch (e: Exception) {
        Log.e(TAG, "Net-Lockdown arm fehlgeschlagen", e)
        runCatching { authorizer.revert() }
        ArmResult.Failed(e.message ?: e.toString())
    }

    fun disarm() {
        authorizer.revert()
        store.saveDesiredArmed(false)
        appContext.startService(Intent(appContext, WardenVpnService::class.java).setAction(WardenVpnService.ACTION_STOP_TUNNEL))
        logStore.append(Log.WARN, TAG, "Net-Lockdown entschärft (Always-On-VPN entfernt)")
    }

    /** Boot-Reconciliation: wendet den persistierten Soll-Zustand an, wenn er vom Ist-Zustand
     * abweicht — s. [de.ble1st.warden.boot.RegistryReconciliationReceiver.reconcileNetLockdown].
     * Die eigentliche Soll-vs-Ist-Entscheidung steckt in [NetLockdownReconcileDecision] (framework-
     * frei, unit-testbar); diese Methode führt nur noch die Aktion aus. */
    fun reconcile() {
        when (NetLockdownReconcileDecision.action(store.loadDesiredArmed(), authorizer.isActive())) {
            NetLockdownReconcileDecision.Action.Arm -> {
                val result = arm()
                if (result != ArmResult.Success) {
                    logStore.append(Log.ERROR, TAG, "Boot-Reconciliation arm fehlgeschlagen: $result")
                }
            }
            NetLockdownReconcileDecision.Action.Disarm -> disarm()
            NetLockdownReconcileDecision.Action.NoOp -> Unit
        }
    }

    /** Muss bei **jeder** Firewall-Policy-Änderung während aktiver Netz-Sperre erneut aufgerufen
     * werden — s. [NetLockdownAuthorizer]-Klassendoc ("zwei unabhängige Bypass-Mechanismen").
     * No-op, wenn Lockdown gerade nicht scharf ist. Stößt zusätzlich einen Tunnel-Reload an, damit
     * auch [WardenVpnService]s eigene `Builder.addDisallowedApplication`-Liste synchron bleibt. */
    fun resyncLockdownAllowlist(allowedPackages: Set<String>) {
        if (!authorizer.isActive()) return
        try {
            authorizer.apply(allowedPackages)
            appContext.startService(Intent(appContext, WardenVpnService::class.java).setAction(WardenVpnService.ACTION_RELOAD_TUNNEL))
        } catch (e: Exception) {
            Log.e(TAG, "Lockdown-Allowlist-Resync fehlgeschlagen", e)
        }
    }

    private fun currentFirewallAllowedPackages(): Set<String> =
        NetworkFirewallPolicyStore(NetworkFirewallPolicyStore.buildEnvelopeFile(appContext)).allowedPackageNames()

    private fun requestBatteryOptimizationExemptionOnce() {
        val packageUri = Uri.parse("package:${appContext.packageName}")
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
            .onFailure { Log.w(TAG, "Akku-Optimierungs-Ausnahme konnte nicht angefordert werden", it) }
    }

    companion object {
        private const val TAG = "NetLockdown"
    }
}
