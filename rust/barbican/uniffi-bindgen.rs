// ⏸ PAUSIERT (2026-08-27): "Netz-Sperre" ist vorübergehend deaktiviert — Live-Test auf dem
// physischen Testgerät fand nach mehreren echten Bugfixes (siehe Commit 7252396 auf App-Seite und
// das Memo warden-netzsperre-feature-2026-08-27) einen weiterhin ungeklärten Kernfehler: die
// DNS-Blockliste/NAT-Relay verarbeitet auf einem frisch aufgebauten Tunnel keinen Traffic mehr.
// Diese Crate ist deshalb aus rust/Cargo.tomls `members` entfernt — ein `cargo build`/`cargo test`
// direkt in diesem Verzeichnis schlägt bewusst mit einem Workspace-Fehler fehl (nicht in
// `members`, nicht `exclude`d), statt still falsch zu bauen. Zum Reaktivieren: "barbican" wieder
// zu rust/Cargo.tomls `members` hinzufügen, diesen Banner-Kommentar aus jeder Datei entfernen,
// Kernfehler zuerst klären.

// Erzeugt Kotlin-Bindings aus der kompilierten Library — identisches Muster zu
// rust/engine/uniffi-bindgen.rs (s. dortiger Kommentar).

fn main() {
    uniffi::uniffi_bindgen_main()
}
