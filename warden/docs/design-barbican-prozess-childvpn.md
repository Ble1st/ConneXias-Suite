# Design-Dokument: Barbican als eigener Prozess + Concord-Anbindung + ChildVPN

**Datum:** 2026-08-31
**Status:** Schritt 1 (Prozess-Split), Schritt 2 (Concord-Rückkanal) und Schritt 3 (ChildVPN-MVP)
umgesetzt. Der zunächst blockierende, vorbestehende Netz-Sperre-Deadlock (s. Abschnitt 4, "Live-Test
durchgeführt, zunächst negativ, dann root-ursächlich behoben"; Details in CLAUDE.md, Abschnitt
"Netz-Sperre") ist seit 2026-08-31 behoben und live verifiziert — ChildVPN erreicht jetzt zuverlässig
`spawn_transport_connect`/einen echten, `protect()`-geschützten Socket zur VPS. Ein vollständiger
WireGuard-Handshake gegen eine echte VPS wurde nicht separat re-verifiziert (Nutzer testet das
selbst).
**Referenz:** `/home/gerd/Schreibtisch/ConneXias-Framework/` (Ursprungsprojekt, insb. `core/ipc/src/main/aidl/.../IConcordBus.aidl`, `barbican/`)

**VPS-Infrastruktur:** läuft bereits (Info vom Nutzer, 2026-08-31) — kein eigener Aufbauschritt in
diesem Plan nötig, nur die WireGuard-Peer-Config muss noch gegen sie eingerichtet werden.

## Zielbild

Kein zweites APK — `WardenVpnService` (+ alles, was es lädt: `BarbicanEngine`,
JNA/UniFFI-Bindings, `.so`) läuft künftig in einem eigenen Prozess `de.ble1st.warden:barbican`
innerhalb derselben APK/Signatur. Zwei neue Fähigkeiten kommen dazu: ein echter Rückkanal zu
Concord fürs Audit-Log, und ein zweiter, optionaler WireGuard-Tunnel ("ChildVPN") zur eigenen VPS,
der bei Bedarf den **gesamten** Traffic dorthin umleitet, statt ihn wie heute direkt vom Gerät aus
rauszulassen.

Im ConneXias-Framework-Quellprojekt war Barbican ein komplett separates APK mit AIDL-Concord
(`IConcordBus.aidl`, signature-permission-geschützt, `CallerVerifier`), weil Herald/Sentinel/
Barbican fremde UIDs waren. WireGuard/VPS war dort als Milestone I.2/I.3 geplant, aber nie gebaut.
Der jetzige Weg ist einfacher als das Original: gleiche UID (nur `android:process`, keine zweite
Signatur), also kein `CallerVerifier`/keine Signature-Permission nötig — ChildVPN selbst ist aber
komplettes Neuland, dafür gibt es keine Vorlage im Quellprojekt.

## 1. Manifest: eigener Prozess — ✅ umgesetzt (2026-08-31)

`android:process=":barbican"` auf `WardenVpnService` gesetzt, `WardenApplication.onCreate()` um
einen `Process.myProcessName() != packageName`-Guard ergänzt (alles ab dem Guard ist
Hauptprozess-only: `WardenLockSession`/`ProcessLifecycleOwner`, WorkManager-Scheduling,
Broadcast-Registrierung). `NetLockdownController`/`WardenVpnService`-Klassendocs entsprechend
korrigiert (behaupteten vorher fälschlich "im selben Prozess"). Build verifiziert:
`compileDebugKotlin`, `processDebugMainManifest`, `testDebugUnitTest`, `assembleDebug` (inkl.
`copySentinelApkForDebug`) laufen alle sauber durch. **Noch offen: On-Device-Verifikation gegen
den RX-Freeze-Bug** — dafür gibt es keinen automatisierten DO/DPM-Testharness (s. CLAUDE.md), das
bleibt ein manueller Schritt auf echter Hardware.

```xml
<service
    android:name=".vpn.WardenVpnService"
    android:process=":barbican"
    android:exported="false"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:foregroundServiceType="dataSync">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
</service>
```

`WardenApplication.onCreate()` läuft in **jedem** Prozess der App einmal — braucht einen frühen
`Process.myProcessName()`-Guard, damit Hauptprozess-only-Initialisierung
(`ProcessLifecycleOwner`/`WardenLockSession`, WorkManager-Scheduling) im `:barbican`-Prozess nicht
unnötig/doppelt läuft.

## 2. Was sich dadurch ändert — und was nicht

| Kanal | Heute | Nach dem Split |
|---|---|---|
| Steuerung (Start/Stopp/Reload/Blocklist-Update) | `Intent`-basiert (`NetLockdownController` → `startService`) | **unverändert** — funktioniert cross-process von Haus aus |
| Config lesen (Blockliste, Firewall-Allowlist, künftig ChildVPN-Config) | Direktzugriff `EnvelopeFile`+Keystore | **unverändert** — mehrere gleichzeitige Leser sind unproblematisch, Schreiben bleibt exklusiv im Hauptprozess |
| Ereignisse zurück (Tunnel-Status, Engine-Absturz, ChildVPN-Handshake) | gar nicht (nur `Log.i/e`, verschwindet in Logcat) | **neu: über Concord** — s. Abschnitt 3, weil `HashChainLogStore` nicht für gleichzeitige Schreiber aus zwei Prozessen ausgelegt ist |
| `ProtectedSocketFactory`-Callback (JNA) | selber Prozess wie `.so` | **unverändert** — muss im selben Prozess wie das native Lib bleiben, das ist bei JNA-Callbacks strukturell so |

## 3. Concord als echter Cross-Process-Kanal

`ConcordBus.kt`s eigener Klassendoc sagt es fast wörtlich voraus: *"Ein späterer, cross-APK-fähiger
`exported`-Service ließe sich um genau diese Klasse wickeln … falls das Projekt das je wieder
braucht."* Jetzt gebraucht — nur cross-Prozess, nicht cross-APK.

- **Neu:** `app/src/main/aidl/de/ble1st/warden/bus/IConcordBus.aidl` — bewusst minimal:
  ```aidl
  interface IConcordBus {
      int getBusVersion();
      /** BARBICAN: reine Ereignis-Meldung in die Hash-Chain — kein Zustand wird gelesen/verändert. */
      boolean reportBarbicanEvent(int priority, String message);
  }
  ```
- `Role.kt`: `Role.BARBICAN` dazu (neben `OWNER`).
- `BusCommand.kt`: neuer Wert `EVENT_REPORT` — eigene Kategorie, dieselbe Logik wie bei
  `LOG_ACCESS`.
- `CapabilityMatrix.kt`: `Role.BARBICAN to setOf(BusCommand.EVENT_REPORT)` — kleinstmögliches
  Privileg, Barbican darf über den Bus nur melden, nichts lesen/schalten. `DESTRUCTIVE` bleibt
  strukturell für jede Rolle blockiert.
- **Neu:** `ConcordBusService : Service()` im Hauptprozess, `exported="false"` genügt (blockiert
  nur fremde UIDs, nicht denselben-App-anderen-Prozess).
- `WardenVpnService`/`BarbicanEngine` im `:barbican`-Prozess wird Bus-**Client**: `bindService`
  auf `ConcordBusService`, meldet Zustandsübergänge. Nebeneffekt: macht den aktuellen, ungelösten
  RX-Freeze-Bug (s. CLAUDE.md-Abschnitt "Netz-Sperre") erstmals im Audit-Log statt nur in Logcat
  sichtbar.

Für den reinen UI-Status ("ChildVPN gerade verbunden?") war hier ursprünglich ein eigener
`ChildVpnStatusStore` nach dem Vorbild von `SentinelPinStateStore` skizziert. **Beim Umsetzen
verworfen** (s. Abschnitt 4, "Bewusst NICHT gebaut"): anders als `SentinelPinStateStore` (Warden
liest dort Zustand von einer fremden APK, geschrieben über einen im Hauptprozess laufenden
Broadcast-Receiver — kein Cross-Prozess-Problem) wäre ein `ChildVpnStatusStore` hier ein
Cross-Prozess-Schreiber-Leser-Paar *innerhalb derselben APK* (`:barbican` schreibt, Hauptprozess
liest) über dieselbe MODE_PRIVATE-SharedPreferences-Datei — von Android als verlässlicher
Synchronisationsmechanismus ausdrücklich nicht empfohlen. Die UI zeigt stattdessen den reinen
Konfigurationszustand (`ChildVpnConfigStore`, ein Datei-Read), echte Ereignisse laufen über den
Concord-Kanal unten.

### Umsetzung — ✅ abgeschlossen (2026-08-31)

- `app/build.gradle.kts`: `buildFeatures.aidl = true`.
- `app/src/main/aidl/de/ble1st/warden/bus/IConcordBus.aidl` — wie oben skizziert, eine Methode.
- `Role.kt`: `Role.BARBICAN` ergänzt.
- `BusCommand.kt`: `EVENT_REPORT` ergänzt.
- `CapabilityMatrix.kt`: `Role.BARBICAN to setOf(BusCommand.EVENT_REPORT)`; neuer Test
  `CapabilityMatrixTest.barbicanMayOnlyReportEvents` (least-privilege explizit geprüft, nicht nur
  implizit durch Abwesenheit).
- `ConcordBus.kt`: `authorize()`/`log()` auf einen `role`-Parameter verallgemeinert (Default
  `Role.OWNER`, alle bestehenden Aufrufstellen unverändert), neue Methode
  `reportBarbicanEvent(priority, message)` mit `Role.BARBICAN`.
- `ConcordBusService.kt` (neu) — dünner AIDL-Stub-Wrapper im Hauptprozess, `exported="false"` im
  Manifest registriert (kein `android:process`).
- `BarbicanConcordClient.kt` (neu) — Bus-Client, bindet einmalig in `WardenVpnService.onCreate()`,
  FIFO-Warteschlange (max. 20) für Ereignisse vor Verbindungsstand, `disconnect()` in `onDestroy()`.
- `WardenVpnService.kt`: drei bisher stumme Stellen melden jetzt über den Bus statt nur in Logcat
  zu verschwinden — TUN-`establish()`-Fehlschlag (ERROR), Rust-Tunnel-Start-Exception (ERROR),
  ein bislang verschluckter `stopCapturedTunnel()`-Fehlschlag (WARN) — plus ein INFO-Eintrag bei
  erfolgreichem Start (mit dem `running`-Flag, das ausdrücklich NICHT als Beweis eines
  tatsächlich arbeitenden Engine-Threads gilt, s. RX-Freeze-Bug-Kommentar in der Datei).
- Build verifiziert: `compileDebugKotlin` (inkl. `compileDebugAidl`), `testDebugUnitTest`,
  `assembleDebug`, `lintDebug` laufen alle sauber durch.

## 4. ChildVPN — vollständige Traffic-Umleitung

Mentales Modell: lokaler Barbican-Tunnel = **Policy** (Kill-Switch + DNS-Blockliste, entscheidet
ALLOW/BLOCK — blockierte Domains bleiben lokal, NXDOMAIN, verlassen das Gerät nie). ChildVPN =
**Egress-Transport** für alles, was die Policy passiert. "über UDP" beschreibt nur den äußeren
WireGuard-Transport (immer UDP) — innen läuft ganz normal TCP *und* UDP durch, wie bei jedem
Multihop-VPN.

**Zwei Egress-Modi in `engine.rs`, exklusiv:**

- **Direct** (heutiges Verhalten, ohne ChildVPN-Config): erlaubtes Paket → `nat.rs`s
  Per-Flow-Socket via `ProtectedSocketFactory` → echtes Ziel.
- **ChildVPN-armed** (neu): erlaubtes Paket → `Tunn::encapsulate()` (neue Dependency `boringtun`,
  Cloudflares reine Userspace-WireGuard-Implementierung — kein eigener Krypto-Code, dieselbe
  "bewährte Primitive statt Eigenbau"-Linie wie `rust/engine`) → verschlüsseltes WireGuard-Paket →
  **derselbe** `ProtectedSocketFactory.openUdp()`-Callback wie heute, nur als Transport zur VPS
  statt zum echten Ziel. Rückweg spiegelbildlich über `Tunn::decapsulate()`.

`nat.rs`s Per-Flow-Socket-Maschinerie (`spawn_tcp_connect`/`spawn_udp_connect`,
`PENDING_TCP_FDS`/`PENDING_UDP_FDS`) wird im ChildVPN-Modus komplett übersprungen — genau das
Subsystem, das aktuell im Verdacht steht, am RX-Freeze beteiligt zu sein. Kein Ziel des Umbaus,
aber ein plausibler Nebeneffekt.

**Fail-safe, keine offene Frage mehr:** VPS nicht erreichbar → Pakete bleiben unzustellbar
(Timeout beim Client), **kein** stilles Zurückfallen auf Direct-Egress. Alles andere wäre eine
unsichtbare Datenschutz-Verschlechterung genau dann, wenn man sich auf den ChildVPN verlässt.
Wardens Always-On+Lockdown-VPN bleibt so oder so der eigentliche Kill-Switch — es gibt keinen Pfad
am TUN vorbei.

**VPS-seitige Voraussetzung:** ein echter WireGuard-Server mit NAT/Routing für den
Tunnel-Adressraum (Standard-`wg-quick`-Server, `nftables`-Masquerade raus ins Internet) — nicht
mehr nur "ein Resolver hosten".

**Konfiguration/UI:** neuer `ChildVpnConfigStore` (`EnvelopeFile`-Pattern wie
`DomainBlocklistStore`) — VPS-Endpunkt, VPS-Public-Key, Wardens eigenes X25519-Schlüsselpaar
(WireGuard-Kurve, separat vom bestehenden Ed25519-Gerätepaar zu generieren). Neuer Abschnitt in
`NetworkScreen`, presence-gated wie der Rest, Ein/Aus-Schalter für "gesamter Traffic über
ChildVPN".

**Korrektur beim Umsetzen (2026-08-31):** Wardens eigenes Schlüsselpaar wird NICHT auf dem Gerät
generiert — der oben skizzierte Satz stand im Widerspruch zur weiter unten dokumentierten
"Entschieden: Onboarding der VPS-Konfiguration"-Entscheidung (WireGuard-Standard-Config-Import).
Ein normaler `wg-quick`-Client-Konfigurationstext, wie ihn jedes VPS-seitige WireGuard-Setup-Skript
für einen neuen Peer ausgibt, enthält bereits das für DIESEN Client generierte `PrivateKey` im
`[Interface]`-Abschnitt — genau das importiert Warden 1:1, kein separater On-Device-Keygen-Schritt.

### Umsetzung — ✅ abgeschlossen (2026-08-31)

**Rust (`rust/barbican`):**

- `Cargo.toml`: `boringtun = "0.7"` (verifiziert gegen den echten Quellcode v0.7.1 von crates.io —
  `default = []`, eine Dependency ohne Extra-Features kompiliert nur `noise`+`x25519`, nicht die
  unnötigen `device`/`ffi-bindings`/`jni-bindings`-Extras).
- `childvpn.rs` (neu, ~350 Zeilen inkl. Tests) — eigenes globales `Mutex<Option<Arc<ChildVpnState>>>`
  (`CHILD_VPN`), exakt dasselbe Muster wie `engine.rs`s `ENGINE`/`BLOCKLIST`. Drei UniFFI-Exports:
  `set_child_vpn_config(...)` (nimmt Schlüssel als rohe `Vec<u8>`, nicht Base64-Text — die
  Base64-Decodierung passiert Kotlin-seitig, kein zusätzlicher `base64`-Crate nötig),
  `clear_child_vpn_config()`, `is_child_vpn_armed()`. Transportsocket-Beschaffung folgt exakt
  `engine.rs`s `spawn_udp_connect`/`PENDING_UDP_FDS`-Muster (asynchron, nie synchron im Engine-Loop,
  ein `protect()`-geschützter Socket über denselben `ProtectedSocketFactory`-Callback wie jeder
  NAT-Relay-Socket). `drive_tick(...)` ist der einzige Einstiegspunkt für `engine.rs`: übernimmt
  einen fertig beschafften Transportsocket, treibt `Tunn::update_timers()` (Handshake-Initiierung/
  -Retry/Keepalive — läuft bei einer frisch konfigurierten Verbindung von selbst an, sobald das
  erste echte Paket durch `Tunn::encapsulate()` läuft, nicht durch `update_timers()` allein, s.
  Testkommentar in `childvpn.rs`), verschlüsselt/versendet echten Traffic, entschlüsselt VPS-Antworten
  und schreibt sie direkt auf den TUN — reines Layer-3-Passthrough, keine eigene NAT-Tabelle.
  Jede verwendete `boringtun`-API-Stelle (inkl. der "bei `WriteToNetwork` mit leerem Datagramm
  wiederholen, bis `Done`"-Aufrufkonvention) wurde gegen den echten heruntergeladenen Quellcode
  verifiziert, nicht aus dem Gedächtnis übernommen. Fail-safe durchgehend: kein Transportsocket ⇒
  Paket wird verworfen, kein Rückfall auf Direct-Egress.
- `engine.rs::run_engine_loop`: neue Verzweigung ganz am Loop-Anfang — `childvpn::is_child_vpn_armed()`
  ⇒ kompletter Bypass von smoltcp/NAT/DNS-Filter für diesen Tick, delegiert an `childvpn::drive_tick`.
  Ein `clear_child_vpn_config()`-Aufruf lässt den nächsten Tick nahtlos in den Direct-Mode-Pfad
  zurückfallen, kein TUN-Neuaufbau nötig.
- `lib.rs`: `childvpn`-Modul registriert, drei neue Funktionen re-exportiert.
- Tests: `childvpn::tests::full_handshake_between_two_tunns_succeeds` (zwei `Tunn`-Instanzen,
  vollständiger WireGuard-Handshake inkl. echtem Datenverkehr, reine In-Process-Protokollprüfung,
  kein Socket/Thread nötig), plus zwei kleinere. `cargo test`/`cargo clippy --all-targets -D
  warnings` beide sauber (21/21 Tests grün, keine Clippy-Warnungen); `cargo test --workspace` (inkl.
  `connexias-engine`) ebenfalls grün.
- `build-android-barbican.sh` real ausgeführt: alle vier ABIs (`arm64-v8a`/`armeabi-v7a`/`x86`/
  `x86_64`) gebaut, Kotlin-Bindings (`uniffi.connexias_barbican.*`) regeneriert — kein Trockenlauf,
  echte `.so`-Dateien und echter generierter Kotlin-Code liegen jetzt in `app/src/main/`. Der
  Skript-Header trug bis dahin noch fälschlich den "⏸ PAUSIERT (2026-08-27)"-Hinweis aus der Zeit
  vor der Reaktivierung (2026-08-29) — korrigiert.

**Kotlin (`app/src/main/java/de/ble1st/warden/`):**

- `domain/netlock/ChildVpnConfigParser.kt` (neu, framework-frei) — parst wg-quick-Standardtext
  (`[Interface]`/`[Peer]`, case-insensitive) zu `ChildVpnConfig`. `ChildVpnConfigParseError` als
  sealed class für gezielte Fehlermeldungen statt eines generischen Fehlschlags. JVM-Unit-Tests
  (`ChildVpnConfigParserTest`), alle grün.

  **Korrigiert 2026-09-01:** ursprünglich wurden `Address`/`DNS`/`AllowedIPs` allesamt ignoriert,
  mit der Begründung "reines Layer-3-Passthrough braucht kein eigenes `wg0`-Interface mit
  zugewiesener Adresse". Für `Address` war das die Root-Ursache des Fehlers "verbunden, aber kein
  Internet": es gibt zwar kein eigenes `wg0`, aber der TUN übernimmt dessen Rolle vollständig, und
  WireGuards Cryptokey-Routing auf der Gegenseite verwirft jedes entschlüsselte Paket, dessen
  Quell-IP nicht zu `AllowedIPs` des Peers passt. `Address` ist deshalb jetzt Pflichtfeld und
  bestimmt `VpnService.Builder.addAddress(...)`, `DNS` wird optional übernommen. Nur `AllowedIPs`
  wird weiterhin bewusst ignoriert ("gesamter Traffic" ist die Anforderung). Volle Herleitung s.
  `CLAUDE.md`-Abschnitt "Netz-Sperre".
- `netlock/ChildVpnConfigStore.kt` (neu) — `EnvelopeFile`-Pattern wie `DomainBlocklistStore`, aber
  zwingend verschlüsselt (anders als Domain-Namen ist `privateKey` echtes Schlüsselmaterial).
  Speichert die bereits geparsten Felder binär, nicht den rohen Text. Seit 2026-09-01 mit
  Formatversion (v2, wegen der neuen Adressfelder); ein v1-Satz ist mangels Adresse nicht
  migrierbar und wird beim Lesen mit klarer Meldung abgelehnt — eine bestehende Gerätekonfiguration
  muss also einmalig neu eingelesen werden.
- `netlock/BarbicanEngine.kt`: drei neue Facade-Methoden (`setChildVpnConfig`/
  `clearChildVpnConfig`/`isChildVpnArmed`) über die neuen UniFFI-Exports.
- `vpn/WardenVpnService.kt`: `applyChildVpnConfig()` (liest `ChildVpnConfigStore` frisch, ruft
  `BarbicanEngine.setChildVpnConfig`/`clearChildVpnConfig`, meldet Erfolg/Fehler über den
  Concord-Kanal aus Schritt 2) wird bei jedem Tunnelstart aufgerufen (wichtig nach einem
  `:barbican`-Prozessneustart, der jeden Rust-statischen Zustand verliert) sowie über eine neue
  `ACTION_UPDATE_CHILD_VPN` — kein TUN-Neuaufbau nötig, identisches Muster zu
  `ACTION_UPDATE_BLOCKLIST`/`updateBlocklist()`.
- `netlock/NetLockdownController.kt`: neue `resyncChildVpn()`, Gegenstück zu `resyncBlocklist()`.
- `ui/NetworkScreen.kt`/`ui/WardenStatusActivity.kt`: neuer `ChildVpnSection`-Abschnitt (Text-Paste
  für die wg-quick-Config, Übernehmen/Entfernen, Statuszeile "Konfiguriert: host:port"), verdrahtet
  nach demselben `LaunchedEffect`+`Dispatchers.IO`+Neuladen-nach-Änderung-Muster wie die Blockliste.
  438+9 neue `strings.xml`-Einträge unter dem `NetworkScreen.kt`-Kommentarblock.

**Bewusst NICHT gebaut — abweichend vom ursprünglichen Reihenfolge-Punkt 3 (Abschnitt 6):**

- **Kein separater `ChildVpnStatusStore`.** Grund, beim Umsetzen erkannt: `libconnexias_barbican.so`
  und jeder statische Rust-Zustand (`CHILD_VPN`, `is_child_vpn_armed()`) existieren **pro Prozess**,
  nicht pro APK — ein Aufruf aus Wardens Hauptprozess (wo die UI läuft) würde nur die dortige,
  eigene, nie getunnelte Kopie dieses Zustands lesen/setzen, mit null Wirkung auf den tatsächlich in
  `:barbican` laufenden Tunnel. Eine SharedPreferences-Datei als Cross-Prozess-Statuskanal wäre
  möglich gewesen, aber Android rät von MODE_PRIVATE-SharedPreferences als verlässlichem
  Cross-Prozess-Synchronisationsmechanismus ausdrücklich ab. Stattdessen: die UI zeigt den reinen
  *Konfigurations*-Zustand (`ChildVpnConfigStore.load() != null`, ein Datei-Read, prozessübergreifend
  sicher) statt eines Live-"verbunden?"-Status, und echte Aktivierungs-/Fehler-Ereignisse laufen über
  den bereits vorhandenen Concord-Kanal aus Schritt 2 ins Audit-Log — dieselbe "manche Diagnosezustände
  sind nicht prozessübergreifend verfügbar, dafür gibt es Concord"-Haltung, die Schritt 2 für den
  RX-Freeze-Bug bereits etabliert hat.
- **Kein Presence-Gate speziell für den ChildVPN-Abschnitt.** `NetworkScreen` hängt bereits komplett
  hinter `WardenLockActivity` (wie jeder andere Bildschirm in `WardenStatusActivity`) — ein
  zusätzliches Gate nur für diesen einen Abschnitt wäre eine zweite, redundante Zugriffskontrolle
  ohne echten Zugewinn.
- **QR-Code-Import nachträglich ergänzt (2026-08-31, explizite Nutzeranforderung "config sol als
  qrcode scannbar sein"), korrigiert die ursprüngliche Entscheidung hier.** Ursprünglich bewusst
  weggelassen ("kein klarer Bedarf, solange niemand explizit danach gefragt hat") — genau dieser
  Fall trat kurz darauf ein. Umgesetzt exakt wie hier vorhergesehen, ohne `ChildVpnConfigParser`
  anzufassen: `com.journeyapps:zxing-android-embedded` (Version 4.3.0, per POM verifiziert einzige
  transitive Abhängigkeit `com.google.zxing:core` — kein Play-Services/ML-Kit, läuft also auch ohne
  GMS) liefert über `ScanContract`/`ScanOptions` den gescannten Rohtext direkt in dasselbe
  `draft`-Textfeld, das auch der Paste-Pfad befüllt; ein Scan übernimmt nicht automatisch, sondern
  erfordert weiterhin den bewussten "Übernehmen"-Tap — wichtig, weil hier echtes Schlüsselmaterial
  durch die Luft kommt. `CAMERA` ist eine echte, an den "QR-Code scannen"-Tap gebundene
  Laufzeit-Berechtigungsanfrage (`ActivityResultContracts.RequestPermission()`), nicht per
  `DevicePolicyManager.setPermissionGrantState` selbst gewährt wie `READ_PHONE_STATE`/
  `ACCESS_FINE_LOCATION` anderswo — ein Device-Owner-Kamerazugriff soll kein dauerhaft scharf
  geschalteter Ambient-Zustand sein. Eine Ablehnung zeigt eine eigene Fehlerzeile
  (`network_child_vpn_camera_permission_denied`) statt still nichts zu tun; Text-Paste bleibt
  parallel nutzbar. `CaptureActivity` ist manifest-deklariert und braucht deshalb keine eigene
  R8-Keep-Regel (AGP-aapt-generierte Regeln reichen, per echtem `minifyReleaseWithR8`-Lauf
  verifiziert — dieselbe Konvention wie in CLAUDE.md für WorkManager-`ListenableWorker`s
  beschrieben).

**Live-Test durchgeführt, zunächst negativ, dann root-ursächlich behoben (2026-08-31).** Nutzer hat
eine echte VPS-Config eingefügt (`Konfiguriert: <VPS-IP>:51820` in der UI sichtbar, Speicherung/
Anwendung also korrekt), aber die Verbindung blieb zunächst inaktiv — derselbe, bereits für
Direct-Mode dokumentierte Bug (s. CLAUDE.md, Abschnitt "Netz-Sperre"). Die dortige ursprüngliche
Ferndiagnose ("Engine-Loop-Thread stirbt/startet nie") war in der Mechanik falsch, in der Wirkung
aber richtig: die tatsächliche Ursache war ein Mutex-Deadlock in `stop_captured_tunnel()`
(`engine.rs`) — die `ENGINE`-Sperre blieb während des blockierenden Thread-Joins gehalten, sodass ein
nachfolgender `start_captured_tunnel()`-Aufruf (z. B. nach einem Disarm→Rearm-Zyklus, den
Android bei Always-On+Lockdown-VPN fast augenblicklich selbst auslöst) unbegrenzt auf dieselbe
Sperre wartete, bevor er auch nur einen einzigen ChildVPN-Zweig erreichen konnte. Volle
Herleitung/Fix in CLAUDE.md, Abschnitt "Netz-Sperre" → "Root-caused and fixed". Live-verifiziert mit
der bereits gespeicherten echten VPS-Config: nach dem Fix läuft `run_engine_loop` an, `drive_tick`
wird aufgerufen, `set_child_vpn_config` feuert, und `spawn_transport_connect` öffnet erfolgreich
einen echten, `protect()`-geschützten UDP-Socket zur VPS (per `/proc/net/udp6` bestätigt, Remote-Port
passend zum konfigurierten WireGuard-Endpoint) — alles innerhalb desselben Tunnel-Start-Aufrufs, der
vorher unbegrenzt hing. Ein vollständiger WireGuard-Handshake gegen die reale VPS wurde in dieser
Runde nicht mehr separat verifiziert (liegt beim Nutzer, s. "childvpn mach ich selber").

## 5. Build-Anpassungen — ✅ alle umgesetzt/verifiziert (2026-08-31)

- `app/build.gradle.kts`: `buildFeatures { aidl = true }` ergänzt (AGP 9 braucht das explizit für
  `.aidl`-Compilierung) — `compileDebugAidl`/`compileReleaseAidl` laufen seither sauber durch.
- `rust/barbican/Cargo.toml`: `boringtun` als neue Dependency — bestehender CI-`rust`-Job
  (fmt/clippy/test) deckt sie automatisch mit ab; `cargo test --workspace` lokal verifiziert.
- `app/src/release/keepRules/barbican.keep`: **nicht angefasst, tatsächlich verifiziert statt nur
  angenommen** — die dortige `-keep class uniffi.connexias_barbican.** { *; }`-Regel ist bereits
  ein Blanket-Keep, deckt die neu generierten ChildVPN-Bindings automatisch mit ab, kein AIDL-Code
  betroffen (AIDL-generierter Kotlin-Code liegt in `de.ble1st.warden.bus`, nicht im
  UniFFI-Namensraum). `:app:minifyReleaseWithR8` real ausgeführt (nicht nur angenommen) — sauber,
  keine neuen Warnungen.

## 6. Reihenfolge

1. **Prozess-Split zuerst, isoliert.** — ✅ umgesetzt (s. Abschnitt 1).
2. **Concord-Rückkanal** (AIDL-Service, `Role.BARBICAN`, `EVENT_REPORT`) — ✅ umgesetzt (s.
   Abschnitt 3), sofort nützlich unabhängig vom ChildVPN.
3. **ChildVPN-MVP**: ein VPS-Peer, boringtun-Integration, Direct/ChildVPN-Modus-Umschaltung,
   Config-Store+UI — ✅ umgesetzt (s. Abschnitt 4); bewusst ohne separaten `ChildVpnStatusStore`,
   s. dortige Begründung. Noch offen: echte Live-Verifikation gegen eine reale VPS auf dem
   physischen Testgerät (nur Protokoll-/Build-Korrektheit wurde in dieser Runde geprüft).
4. Später, nicht Teil dieses Plans: mehrere VPS-Peers, Auswahl pro Profil/Ort.

## Entschieden: Onboarding der VPS-Konfiguration (2026-08-31)

WireGuard-Standard-Config-Import (Text/QR, `wg-quick`-Format) statt eigenem Formular — spart ein
eigenes Konfigurationsformat, die VPS läuft bereits und hat vermutlich schon eine passende
`wg-quick`-Config. `ChildVpnConfigStore` (Abschnitt 4) parst also ein Standard-`[Interface]`/
`[Peer]`-Config-Dokument statt einzelner Formularfelder.
