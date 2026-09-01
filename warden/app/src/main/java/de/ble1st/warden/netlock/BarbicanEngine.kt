package de.ble1st.warden.netlock

import uniffi.connexias_barbican.ProtectedSocketFactory
import uniffi.connexias_barbican.TunnelStats
import uniffi.connexias_barbican.clearChildVpnConfig as barbicanClearChildVpnConfig
import uniffi.connexias_barbican.isCapturedTunnelRunning as barbicanIsCapturedTunnelRunning
import uniffi.connexias_barbican.isChildVpnArmed as barbicanIsChildVpnArmed
import uniffi.connexias_barbican.setBlocklist as barbicanSetBlocklist
import uniffi.connexias_barbican.setChildVpnConfig as barbicanSetChildVpnConfig
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

    /**
     * ChildVPN (2026-08-31, `docs/design-barbican-prozess-childvpn.md`) scharf schalten — ab dem
     * nächsten `engine.rs`-Tick verzweigt der laufende Tunnel dorthin, s. `childvpn.rs`-Moduldoc.
     * Wirkt nur, solange [startCapturedTunnel] bereits läuft (das ChildVPN-Modul selbst hat keine
     * eigene Tunnel-Lebensdauer, es klinkt sich nur in den bestehenden Engine-Loop ein).
     */
    fun setChildVpnConfig(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
        presharedKey: ByteArray?,
        persistentKeepaliveSecs: UShort?,
        endpointHost: String,
        endpointPort: UShort,
        socketFactory: ProtectedSocketFactory,
    ) = barbicanSetChildVpnConfig(
        privateKey,
        peerPublicKey,
        presharedKey,
        persistentKeepaliveSecs,
        endpointHost,
        endpointPort,
        socketFactory,
    )

    /** Deaktiviert ChildVPN wieder — der nächste Tick fällt zurück in den Direct-Mode-Pfad. */
    fun clearChildVpnConfig() = barbicanClearChildVpnConfig()

    /** `true` = konfiguriert und scharf geschaltet, s. `childvpn.rs::is_child_vpn_armed`-Doc für
     * die genaue Bedeutung (nicht gleichbedeutend mit "Handshake bereits abgeschlossen"). */
    fun isChildVpnArmed(): Boolean = barbicanIsChildVpnArmed()
}
