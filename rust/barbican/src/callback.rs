//! Von Kotlin implementierter Callback-Trait (`WardenVpnService`, `netlock/`-Package) — dasselbe
//! UniFFI-Callback-Interface-Muster wie `connexias_engine::keystore::KeyWrapper`
//! (`#[uniffi::export(foreign)]` auf einem Trait, Kotlin implementiert ihn, Rust ruft synchron
//! auf; s. dortiges Klassendoc für die volle Begründung des Musters).
//!
//! **Warum ein Callback statt eines direkten Rust-`std::net::TcpStream::connect()`:** ein
//! `VpnService`-Tunnel fängt standardmäßig *alle* App-Verbindungen ab, inklusive der eigenen
//! Ausgangs-Sockets, die eine reale Gegenseite für eine NAT-Session öffnen wollen — ohne
//! `android.net.VpnService.protect(socket)` würde ein solcher Socket in einer Routing-Schleife
//! zurück in den eigenen Tunnel landen. `protect()` ist ausschließlich über die Java/Kotlin-API
//! erreichbar (kein NDK-Äquivalent), daher übernimmt Kotlin die eigentliche Socket-Erzeugung,
//! exakt dieselbe "kein NDK-Pfad, daher Callback"-Begründung wie beim `KeyWrapper`/AndroidKeyStore.

use std::fmt;

#[derive(Debug, uniffi::Error)]
pub enum SocketError {
    /// Kotlin-seitiger Socket-Aufbau ist fehlgeschlagen (z. B. Zieladresse nicht erreichbar,
    /// `protect()` abgelehnt, Ressourcenlimit erreicht). Fail-Safe: der Aufrufer (`nat.rs`)
    /// verwirft die betroffene Verbindung, sperrt aber nicht den gesamten Tunnel — ein
    /// einzelner nicht öffenbarer Socket ist kein Grund, die ganze Netz-Sperre zu beenden.
    Failed,
}

impl fmt::Display for SocketError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "Aufbau des geschützten Sockets (Kotlin-Callback) fehlgeschlagen"
        )
    }
}

impl std::error::Error for SocketError {}

/// Von `de.ble1st.warden.netlock.WardenVpnService` implementiert: öffnet einen echten,
/// `VpnService.protect()`-geschützten Socket zur angegebenen realen Zieladresse und gibt dessen
/// rohen Dateideskriptor zurück. Ownership des fd geht dabei an Rust über (Rust schließt ihn,
/// nicht Kotlin) — anders als beim Tun-fd (s. `sinkhole.rs`/`nat.rs`-SAFETY-Kommentare), der
/// weiterhin von Kotlins `ParcelFileDescriptor` verwaltet bleibt und nie von Rust geschlossen
/// wird. Zwei getrennte Methoden statt eines gemeinsamen `open(proto, ...)`, weil TCP/UDP-Aufbau
/// auf der Kotlin-Seite unterschiedliche `java.net`-APIs sind (`Socket` vs. `DatagramSocket`).
#[uniffi::export(foreign)]
pub trait ProtectedSocketFactory: Send + Sync {
    fn open_tcp(&self, dst_ip: String, dst_port: u16) -> Result<i32, SocketError>;
    fn open_udp(&self, dst_ip: String, dst_port: u16) -> Result<i32, SocketError>;
}
