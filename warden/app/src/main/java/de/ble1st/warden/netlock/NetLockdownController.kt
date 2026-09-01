package de.ble1st.warden.netlock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import de.ble1st.warden.domain.netlock.NetLockdownReconcileDecision
import de.ble1st.warden.logging.HashChainLogStore
import de.ble1st.warden.logging.LogStorage
import de.ble1st.warden.vpn.WardenVpnService

/**
 * "Netz-Sperre" (2026-08-27): Warden-seitige Source of Truth für "Net-Lockdown scharf?" —
 * portiert vom ConneXias-Framework-Quellprojekt (`warden-app/vnp/NetLockdownController.kt`), dort
 * gegen ein fremdes Barbican-APK, hier gegen [WardenVpnService] in derselben APK (kein
 * Cross-APK-`startService`-Aufruf mehr nötig — Android startet Wardens eigenen VPN-Service über
 * `setAlwaysOnVpnPackage` ohnehin selbst, s. [NetLockdownAuthorizer]-Klassendoc). **Seit dem
 * Prozess-Split (2026-08-31, `docs/design-barbican-prozess-childvpn.md`) läuft [WardenVpnService]
 * in einem eigenen Prozess (`android:process=":barbican"`) statt in Wardens Hauptprozess** — der
 * `startService(Intent)`-Aufruf hier bleibt davon unberührt, `Intent`-basierte Service-Steuerung
 * funktioniert cross-process innerhalb derselben App unverändert, kein AIDL/Binder nötig. Arm/
 * disarm rührt DPMs Always-On-VPN an, persistiert den Soll-Zustand für die Boot-Reconciliation.
 * [disarm] räumt DPM immer auf, auch wenn [WardenVpnService] gerade tot ist (Invariante 1b aus dem
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
        // Bewusst KEIN `VpnService.prepare(appContext)`-Aufruf hier (kurzzeitig in Commit e5dbe70
        // versucht, wieder entfernt 2026-08-29): `prepare()` fragt eine komplett andere, ältere
        // Consent-Buchführung ab (den "vom Nutzer zuletzt bestätigten VPN-App"-Zustand) als
        // `setAlwaysOnVpnPackage()` — für Warden, das nie über den `prepare()`-Consent-Dialog
        // gelaufen ist, liefert `prepare()` auf dem allerersten Scharfschalten praktisch immer
        // einen nicht-null Consent-Intent zurück, unabhängig vom Device-Owner-Status. Ein Abbruch
        // an dieser Stelle (wie in e5dbe70) hätte [arm] dadurch strukturell nie ein einziges Mal
        // erfolgreich durchlaufen lassen — [authorizer].apply() darunter ist der tatsächlich
        // zuständige, laut AOSP-Javadoc consent-freie Weg für einen Device-Owner-Aufrufer (s.
        // [NetLockdownAuthorizer]-Klassendoc), das war schon vor e5dbe70 korrekt.
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
        // Zuerst den VPN-Tunnel stoppen (wichtig: BEVOR Always-On VPN entfernt wird!)
        // startService mit Action funktioniert innerhalb der gleichen App auch bei exported=false
        val stopIntent = Intent(appContext, WardenVpnService::class.java).apply {
            action = WardenVpnService.ACTION_STOP_TUNNEL
        }
        appContext.startService(stopIntent)
        
        // Jetzt den Always-On VPN Lockdown entfernen
        authorizer.revert()
        store.saveDesiredArmed(false)
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

    /** Gegenstück zu [resyncLockdownAllowlist] für die andere Hälfte des Tunnel-Zustands: muss
     * nach jeder Änderung der Domain-Blockliste ([DomainBlocklistStore.addDomain]/[removeDomain])
     * aufgerufen werden, damit ein bereits laufender Tunnel die neue Liste übernimmt. Anders als
     * [resyncLockdownAllowlist] löst das **keinen** TUN-Neuaufbau aus (s. [WardenVpnService
     * .updateBlocklist]-Kommentar) — die Rust-Seite liest die Blockliste bei jeder DNS-Anfrage
     * frisch aus einem vom Tunnel-Lebenszyklus unabhängigen Speicher, ein `setBlocklist`-Aufruf
     * wirkt sofort, ohne eine einzige bestehende NAT-Session zu unterbrechen. No-op, wenn Lockdown
     * gerade nicht scharf ist: dann liest der nächste reguläre [arm] die dann bereits aktuelle
     * Liste ohnehin frisch ein. */
    fun resyncBlocklist() {
        if (!authorizer.isActive()) return
        appContext.startService(Intent(appContext, WardenVpnService::class.java).setAction(WardenVpnService.ACTION_UPDATE_BLOCKLIST))
    }

    /** ChildVPN-Gegenstück zu [resyncBlocklist] (2026-08-31, `docs/design-barbican-prozess-
     * childvpn.md`) — muss nach jeder Änderung von [ChildVpnConfigStore] (gespeichert oder gelöscht)
     * aufgerufen werden, damit ein bereits laufender Tunnel das übernimmt. Der eigentliche Aufruf
     * an die Rust-Engine passiert ausschließlich in [WardenVpnService] selbst (läuft in `:barbican`,
     * s. dortiges Klassendoc "ChildVPN" für die Begründung, warum das nicht von hier aus direkt
     * geht) — diese Methode stößt nur den Cross-Prozess-Intent an, identisches Muster wie
     * [resyncBlocklist]. No-op, wenn Net-Lockdown gerade nicht scharf ist (kein laufender Tunnel,
     * den es zu aktualisieren gäbe) — der nächste reguläre [arm] liest die dann bereits aktuelle
     * Config ohnehin frisch ein. */
    fun resyncChildVpn() {
        if (!authorizer.isActive()) return
        appContext.startService(Intent(appContext, WardenVpnService::class.java).setAction(WardenVpnService.ACTION_UPDATE_CHILD_VPN))
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
