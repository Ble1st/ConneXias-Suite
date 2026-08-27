// ⏸ PAUSIERT (2026-08-27): "Netz-Sperre" ist vorübergehend deaktiviert — Live-Test auf dem
// physischen Testgerät fand nach mehreren echten Bugfixes (siehe Commit 7252396 auf App-Seite und
// das Memo warden-netzsperre-feature-2026-08-27) einen weiterhin ungeklärten Kernfehler: die
// DNS-Blockliste/NAT-Relay verarbeitet auf einem frisch aufgebauten Tunnel keinen Traffic mehr.
// Diese Crate ist deshalb aus rust/Cargo.tomls `members` entfernt — ein `cargo build`/`cargo test`
// direkt in diesem Verzeichnis schlägt bewusst mit einem Workspace-Fehler fehl (nicht in
// `members`, nicht `exclude`d), statt still falsch zu bauen. Zum Reaktivieren: "barbican" wieder
// zu rust/Cargo.tomls `members` hinzufügen, diesen Banner-Kommentar aus jeder Datei entfernen,
// Kernfehler zuerst klären.

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

pub mod callback;
pub mod dns_filter;
pub mod engine;
pub mod nat;
pub mod sinkhole;

pub use callback::{ProtectedSocketFactory, SocketError};
pub use engine::{TunnelError, TunnelStats};
pub use sinkhole::{SinkholeError, is_sinkhole_running, start_sinkhole, stop_sinkhole};

uniffi::setup_scaffolding!();
