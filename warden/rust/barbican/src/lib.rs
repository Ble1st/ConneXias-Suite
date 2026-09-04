//! `connexias-barbican` — "Netz-Sperre" (2026-08-27): Warden-interne Portierung des
//! ConneXias-Framework-Quellprojekts `barbican`-Moduls (dort ein eigenes, zweites APK; hier
//! direkt in Warden gelinkt, kein Cross-APK-IPC nötig).
//!
//! - `sinkhole`: Milestone I.1 aus dem Quellprojekt, unverändert — reiner Lese-und-Verwerfen-Pfad.
//! - `dns_filter`: Blocklisten-Prüfung für DNS-Queries (QNAME-Parsing, NXDOMAIN-Synthese).
//! - `nat`: Verbindungstabelle + Paket-Relay für nicht blockierten Traffic (echtes Forwarding,
//!   nicht nur Sinkhole) — geht über das Quellprojekt hinaus (dort nie über Milestone I.1 hinaus
//!   gebaut).
//! - `callback`: von Kotlin implementierter `ProtectedSocketFactory`-Trait (`VpnService.protect()`
//!   -Sockets für die reale Gegenseite jeder NAT-Session) — dasselbe UniFFI-Callback-Interface-
//!   Muster wie `connexias_engine::keystore::KeyWrapper`.
//! - `engine`: öffentliche UniFFI-Exports, verdrahtet die obigen Module zum eigentlichen
//!   Paket-Lese-Loop.
//! - `childvpn`: ChildVPN (2026-08-31, `docs/design-barbican-prozess-childvpn.md`) — optionaler
//!   zweiter, WireGuard-basierter (via `boringtun`) Tunnel zur eigenen VPS, der bei Scharfschaltung
//!   den gesamten Traffic dorthin umleitet statt über `nat`/`dns_filter`. Von `engine::run_engine_loop`
//!   pro Tick angesteuert, s. dortige Verzweigung.

pub mod callback;
pub mod childvpn;
pub mod dns_filter;
pub mod engine;
pub mod nat;
pub mod sinkhole;

pub use callback::{ProtectedSocketFactory, SocketError};
pub use childvpn::{
    ChildVpnError, clear_child_vpn_config, is_child_vpn_armed, set_child_vpn_config,
};
pub use engine::{TunnelError, TunnelStats};
pub use sinkhole::{SinkholeError, is_sinkhole_running, start_sinkhole, stop_sinkhole};

uniffi::setup_scaffolding!();
