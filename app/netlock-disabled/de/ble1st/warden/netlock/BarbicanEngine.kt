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

import uniffi.connexias_barbican.ProtectedSocketFactory
import uniffi.connexias_barbican.TunnelStats
import uniffi.connexias_barbican.isCapturedTunnelRunning as barbicanIsCapturedTunnelRunning
import uniffi.connexias_barbican.setBlocklist as barbicanSetBlocklist
import uniffi.connexias_barbican.startCapturedTunnel as barbicanStartCapturedTunnel
import uniffi.connexias_barbican.stopCapturedTunnel as barbicanStopCapturedTunnel
import uniffi.connexias_barbican.tunnelStats as barbicanTunnelStats

/**
 * "Netz-Sperre" (2026-08-27): dünne Facade über die `connexias-barbican`-UniFFI-Exports —
 * identisches Muster zu [de.ble1st.warden.crypto.Engine] für `connexias-engine` (s. dortiges
 * Klassendoc für die Begründung der Alias-Importe: ohne `as barbicanXxx` würde z. B.
 * `startCapturedTunnel()` innerhalb von `BarbicanEngine.startCapturedTunnel()` sich selbst statt
 * die UniFFI-Funktion aufrufen). Hält [WardenVpnService]/`NetLockdownController` von der
 * generierten `uniffi.connexias_barbican.*`-API frei, damit ein künftiger Rust-API-Wechsel nur
 * hier nachgezogen werden muss.
 */
object BarbicanEngine {

    fun startCapturedTunnel(
        tunFd: Int,
        tunIpv4: String,
        dnsSentinelIpv4: String,
        upstreamDnsIpv4: String,
        socketFactory: ProtectedSocketFactory,
    ) = barbicanStartCapturedTunnel(tunFd, tunIpv4, dnsSentinelIpv4, upstreamDnsIpv4, socketFactory)

    fun stopCapturedTunnel() = barbicanStopCapturedTunnel()

    fun isCapturedTunnelRunning(): Boolean = barbicanIsCapturedTunnelRunning()

    fun setBlocklist(domains: Set<String>) = barbicanSetBlocklist(domains.toList())

    fun tunnelStats(): TunnelStats = barbicanTunnelStats()
}
