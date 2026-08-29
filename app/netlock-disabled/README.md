# Netz-Sperre — pausiert (2026-08-27)

Dieses Verzeichnis parkt den kompletten Warden-seitigen Code der "Netz-Sperre"
(Barbican-VPN/Firewall-Port) außerhalb jedes Gradle-Source-Sets — nichts hier wird mitkompiliert
oder ist irgendwo verkabelt. Grund: nach mehreren im Live-Test gefundenen und behobenen echten
Bugs (fehlende INTERNET-Permission, `protect()`-Thread-Hang, `stopTunnel()`-Deadlock,
DoT-Bypass — Commit `7252396`) blieb ein Kernfehler ungeklärt: auf einem frisch aufgebauten Tunnel
verarbeitet die DNS-Blockliste/das NAT-Relay überhaupt keinen Traffic mehr. Details/volle Historie:
Memo `warden-netzsperre-feature-2026-08-27`.

## Inhalt

- `de/ble1st/warden/netlock/*.kt` — Kotlin-Feature-Package (Controller, Stores, `WardenVpnService`,
  `BarbicanEngine`-Facade, `NetworkLogViewerActivity`).
- `de/ble1st/warden/domain/netlock/NetLockdownReconcileDecision.kt` (+ `test/`-Unit-Test) —
  reine Entscheidungslogik für die Boot-Reconciliation.
- `de/ble1st/warden/ui/NetworkScreen.kt` — Compose-UI ("Netzwerk"-Dashboard-Screen).
- `uniffi/connexias_barbican/connexias_barbican.kt` — autogenerierte UniFFI-Bindings.
- `jniLibs/<abi>/libconnexias_barbican.so` — die vier ABI-Builds der Rust-Engine.
- `keepRules/barbican.keep.txt` — R8-Keep-Regel für `uniffi.connexias_barbican.**`
  (Endung bewusst `.txt` statt `.keep` — jede `.keep`-Datei in `app/src/release/keepRules/` wird
  von AGP automatisch aufgenommen, s. `CLAUDE.md`).

Die Rust-Crate selbst (`rust/barbican/`) bleibt an ihrem Ort, ist aber aus
`rust/Cargo.toml`s `members` entfernt (dort ebenfalls mit Pausiert-Banner in jeder Quelldatei
markiert) — kein zweiter Parkplatz nötig, da sie ohnehin nie Teil des Gradle-Builds war.

## Reaktivieren

1. Kernfehler klären (s. o.) — sonst nicht sinnvoll.
2. Alle `de/...`-Verzeichnisse hier zurück nach `app/src/main/java/...` verschieben, die vier
   `jniLibs/<abi>/*.so` zurück nach `app/src/main/jniLibs/<abi>/`, `keepRules/barbican.keep.txt`
   zurück nach `app/src/release/keepRules/barbican.keep` (Endung zurückändern!).
3. Die Pausiert-Banner-Kommentare am Dateianfang wieder entfernen.
4. Die Wiederverkabelung in den folgenden Dateien rückgängig machen (s. Deaktivierungs-Commit-
   Message für die exakte Diff-Historie): `WardenApplication.kt`, `RegistryReconciliationReceiver
   .kt`, `DeviceLockdownBundle.kt`, `WardenStatusActivity.kt`, `WardenDeviceAdminReceiver.kt`,
   `AndroidManifest.xml`.
5. `rust/Cargo.toml`: `"barbican"` wieder zu `members` hinzufügen, Pausiert-Banner aus den
   `rust/barbican/src/*.rs`-Dateien entfernen.
