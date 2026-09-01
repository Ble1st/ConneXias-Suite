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
import de.ble1st.warden.appmanagement.InstalledAppEntry
import de.ble1st.warden.appmanagement.InstalledAppLister

/**
 * "Netz-Sperre" (2026-08-27): App-seitige Fassade über [NetworkFirewallPolicyStore] für die UI
 * ([de.ble1st.warden.ui.NetworkScreen]) — reicht jede ALLOWED/CAPTURED-Änderung sofort an
 * [NetLockdownController.resyncLockdownAllowlist] weiter, damit DPMs Lockdown-Allowlist nie von
 * Wardens eigener Firewall-Policy abweicht (s. `NetLockdownAuthorizer`-Klassendoc für die
 * "zwei unabhängige Bypass-Mechanismen"-Begründung). [InstalledAppLister] wird direkt
 * wiederverwendet (kein eigenes App-Auflisten nötig, s. Plan Abschnitt 2).
 */
class NetworkFirewallPolicyController(context: Context) {

    private val appContext = context.applicationContext
    private val store = NetworkFirewallPolicyStore(NetworkFirewallPolicyStore.buildEnvelopeFile(appContext))
    private val appLister = InstalledAppLister(appContext)
    private val lockdownController by lazy { NetLockdownController(appContext) }

    fun listApps(): List<InstalledAppEntry> = appLister.listInstalledApps()

    fun modeFor(packageName: String): FirewallMode = store.modeFor(packageName)

    fun setMode(packageName: String, mode: FirewallMode) {
        store.setMode(packageName, mode)
        lockdownController.resyncLockdownAllowlist(store.allowedPackageNames())
    }

    fun allowedPackageNames(): Set<String> = store.allowedPackageNames()
}
