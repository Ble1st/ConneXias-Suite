# ConneXias Suite — Sicherheits-/Robustheits-Audit

Laufendes Audit über alle vier Apps der Suite (Warden, ConneXias Files, ConneXias Kamera,
ConneXias Galerie). Jeder Befund steht hier mit Schweregrad, Fundort und Auflösungsstand; die
App-eigenen READMEs führen dieselben Befunde noch einmal chronologisch als Changelog, aus Sicht
der jeweiligen App.

**Zum Stand dieses Dokuments (2026-09-03):** Die Arbeitsfassungen der einzelnen Durchgänge waren
nie im Repository eingecheckt, obwohl alle vier READMEs auf `analyse.md` verweisen. Diese Fassung
konsolidiert die Befunde aus den vier App-Changelogs und `warden/CLAUDE.md` zu dem Dokument, auf
das die Verweise zeigen. Sie enthält deshalb genau die Befunde, deren Behebung im Repository
nachweisbar ist — kein Befund wurde nachträglich rekonstruiert, der nicht in einem Commit oder
einem Changelog-Eintrag belegt ist. Befunde, die in den Durchgängen geprüft und *verworfen* wurden
(kein echtes Problem), sind dadurch nicht mehr enthalten; sie waren auch für die Verweise nie
relevant.

Abschnittsnummerierung (stabil, die Verweise in den READMEs zeigen darauf):

| Abschnitt | Gegenstand |
|---|---|
| 1 | Warden |
| 2 | ConneXias Files |
| 3 | ConneXias Kamera |
| 4 | ConneXias Galerie |
| 5 | Suite-Schnittstellen (App-übergreifende Intent-Verträge) |
| 6 | Offene Punkte und bewusst akzeptierte Risiken |

Schweregrade: **Hoch** = umgeht oder entwertet eine Sicherheitszusage der App · **Mittel** =
Datenverlust, Fehlfunktion oder eine Schutzlücke in einem Randfenster · **Niedrig** =
Robustheit/Korrektheit ohne unmittelbare Sicherheitsfolge.

---

## 1. Warden

### 1.1 Erster Durchgang (2026-09-02)

| # | Schweregrad | Befund | Stand |
|---|---|---|---|
| 1-01 | Hoch | FRP-Profil (`FactoryResetProtectionSafeguard`) wurde gesetzt, aber die Wirksamkeit war nie überprüfbar — `isActive()` kann den asynchronen GMS-Round-Trip nicht verifizieren | Behoben, soweit möglich: In-App-Warntext sagt die empirisch bestätigte Unzuverlässigkeit jetzt explizit (s. 6.1) |
| 1-02 | Mittel | Notification-Freeze-Pfad ungehärtet | Behoben |
| 1-03 | Mittel | Suite-Pakete (Files/Kamera/Galerie) waren nicht gegen Wardens eigenen Freeze-Pfad geschützt | Behoben — `AppManagementController.SUITE_PACKAGE_NAMES` |

### 1.2 Zweiter Durchgang (2026-09-03) — „Abschnitt 1 vollständig abgearbeitet"

| # | Schweregrad | Befund | Stand |
|---|---|---|---|
| 1-04 | Hoch | **Profil-Apply nimmt Sentinel-Deinstallationsschutz zurück.** `SentinelUninstallProtectionSafeguard` liegt im reversiblen Katalog, aber bewusst in keinem `WardenProfile` — die alte Blanket-Schleife in `WardenProfileApplier` hat ihn bei jedem Profilwechsel still entwaffnet, auch bei der 06:00-Automatik | Behoben — `WardenProfileApplyDecision.NEVER_TOUCHED` |
| 1-05 | Hoch | **Profile knacken Presence-Lockdown über geteilten DPM-Zustand.** Vier IDs (`usb_data_signaling_disabled`, `safe_boot_disabled`, `factory_reset_disabled`, `debugging_features_disabled`) sind dieselben DPM-Bits unter zwei unabhängigen Soll-Zustands-Datensätzen; ein Profil-Apply hat einen presence-gesicherten `LOCKDOWN_MODE_ARM` ohne jede Presence-Prüfung teilweise zurückgerollt | Behoben — `WardenProfileApplyDecision.LOCKDOWN_SHARED_IDS` revertiert nur bei aktivem `DeviceLockdownBundle` |
| 1-06 | Hoch | **Zwei Soll-Zustände für Always-On-VPN.** `NetLockdownController.arm()`/`disarm()` schrieben `NetLockdownStore.desiredArmed` selbst, `DeviceLockdownBundle` rief denselben `NetLockdownAuthorizer` direkt — Boot-Reconcile konnte danach eine Netzsperre ohne Presence-Prüfung neu scharfschalten oder eine presence-scharfgeschaltete wieder abschalten | Behoben — `apply()`/`revert()` schreiben den Store selbst, einzige Wahrheitsquelle für beide Türen |
| 1-07 | Hoch | **Failed-Attempts-Reboot greift genau im BFU-Fenster nicht.** `WardenDeviceAdminReceiver` war nicht `directBootAware` — der Zähler aus `onPasswordFailed` lief erst nach dem ersten Entsperren, also nie in dem Fenster, für das er gedacht ist | Behoben — Manifest-Flag ergänzt (Speicher war bereits Device-Protected) |
| 1-08 | Mittel | **`isActive()` prüft nicht, was `apply()` setzt** (`NetLockdownAuthorizer`): nur `getAlwaysOnVpnPackage()`, nicht das `lockdownEnabled`-Flag — Traffic konnte an einem toten VPN-Prozess vorbeilaufen, während UI und Boot-Reconcile „scharf" meldeten | Behoben — `isAlwaysOnVpnLockdownEnabled(admin)` wird mitgeprüft |
| 1-09 | Mittel | Dasselbe bei `WardenLockTaskAuthorizer`: nur die Lock-Task-Paketliste, nicht die `EMERGENCY_PRESERVING_FEATURES`-Bitmaske — ein halb fehlgeschlagenes `apply()` meldete „autorisiert", obwohl Home/Overview im Kiosk erreichbar geblieben wären | Behoben — beide DPM-Bits werden geprüft |
| 1-10 | Mittel | **USB-Daten am gesperrten Gerät nach Boot wieder an.** `RegistryReconciler` sah beim Boot die Abweichung des dynamischen USB-Auto-Lock-Toggles als Drift und „korrigierte" sie durch Wiedereinschalten der USB-Daten — vor dem Entsperren, also genau im Schutzfenster | Behoben — `RegistryReconcileDecision` mit `neverWeaken: Set<String>` |
| 1-11 | Mittel | **WardenLock ist dauerhaft ein No-Op ohne PIN.** Die Bootstrap-Ausnahme (kein PIN gesetzt → Zugang frei) ist beabsichtigt, aber es gab danach keinerlei sichtbaren Hinweis — ein frisch per QR provisioniertes Gerät stand unbegrenzt mit ungeschütztem Dashboard da | Behoben — tappbare Warnzeile in `StatusCard` (Nag, keine Zwangssperre) |
| 1-12 | Mittel | Sentinel-PIN-Mindestlänge stand noch auf 4, während `:app` seit U-5 auf 6 angehoben war — Sentinel bewacht den einzigen Ausgang aus dem echten Kiosk-Modus | Behoben — `MIN_PIN_LENGTH = 6` für neue PINs, `UNLOCK_MIN_PIN_LENGTH = 4` für den Verify-Pfad |
| 1-13 | Niedrig | `AutoLockTimeoutSafeguard.isActive()` akzeptierte jeden Wert 1–30000 ms statt des exakt gesetzten Werts — Drift wurde nie korrigiert | Behoben — exakter Vergleich |
| 1-14 | Niedrig | **Poisoned-Mutex in `childvpn.rs`.** `state.tunn`/`state.transport_fd` per `.lock().unwrap()` — ein Panic unter gehaltenem Lock hätte das Modul für die gesamte Prozesslebensdauer tot gelegt, unbehebbar auch durch Neustart des Tunnels | Behoben — `.unwrap_or_else(\|p\| p.into_inner())` |

### 1.3 Atomaritäts-/Reihenfolge-Review (2026-09-03, eigener Durchgang)

| # | Schweregrad | Befund | Stand |
|---|---|---|---|
| 1-15 | Mittel | `AntiTheftAlarmController.trigger()` protokollierte den Standort **vor** Ton/Vibration — Provider-Abfrage plus hash-verketteter, verschlüsselnder Log-Schreibvorgang verzögerten messbar genau den Teil, der ohne Verzögerung feuern muss | Behoben — Reihenfolge getauscht, Standort-Log bleibt best-effort |
| 1-16 | Mittel | Audit-Log-Export formatierte und schrieb bis zu ~10 000 Einträge synchron im `ActivityResult`-Callback (Main-Thread) — echtes ANR-Risiko, das die als Vorlage dienende Konfig-Export-Funktion nie hatte | Behoben — `Dispatchers.IO` |

### 1.4 Schnittstellen-Review (2026-09-03)

Zwei Befunde aus dem separaten Review der Warden-internen Schnittstellen, s. Commit
`c1ef8b7`. Beide behoben.

---

## 2. ConneXias Files

### 2.1 Erster Durchgang (2026-09-02)

| # | Schweregrad | Befund | Stand |
|---|---|---|---|
| 2-01 | Hoch | `file_paths.xml` gab den echten Filesystem-Root frei statt nur `/storage` | Behoben |
| 2-02 | Hoch | **Path-Traversal über präparierte Namen.** Neu-Anlegen/Umbenennen/SAF-Import/WebDAV-Upload übernahmen `../`-Anteile aus einem Namen oder einer fremden `DISPLAY_NAME`-Spalte | Behoben — zentrales `sanitizeName` |
| 2-03 | Hoch | **OVERWRITE löschte das Ziel vor der Kopie** — ein Fehlschlag mitten im Kopieren verlor beide Seiten | Behoben — Ziel wird erst nach vollständig verifizierter Kopie gelöscht |
| 2-04 | Mittel | Ordner in sich selbst kopieren/verschieben wuchs endlos | Behoben — erkannt und abgelehnt |
| 2-05 | Mittel | Zip-Extraktion ohne Größenbegrenzung (Zip-Bombe) — der Zip-Slip-Schutz existierte bereits | Behoben — Gesamtgrößen-Limit |
| 2-06 | Mittel | WebDAV: `href` als vollständige URL (Nextcloud) wurde falsch geparst; `MOVE` verließ sich auf den RFC-Default und überschrieb still | Behoben — Parser korrigiert, `Overwrite: F` |
| 2-07 | Mittel | Mehrfach-Teilen setzte kein `ClipData` — Empfänger wie Gmail sahen nur die erste Uri | Behoben |
| 2-08 | Mittel | Ein zweiter Kopier-/Verschiebe-/Lösch-/Zip-Job wurde still verworfen | Behoben — Aktionen sind gesperrt, solange einer läuft (echte Queue s. 6.2) |
| 2-09 | Niedrig | „Entpacken" wurde für RAR/7z/TAR angeboten, die nur mit kryptischem Fehler scheitern konnten | Behoben — nur noch ZIP/JAR |
| 2-10 | Niedrig | `POST_NOTIFICATIONS` war deklariert, wurde aber nie zur Laufzeit angefragt | Behoben |
| 2-11 | Niedrig | Ein `http://`-WebDAV-Server warnte nicht vor der Klartext-Übertragung der Zugangsdaten | Behoben — sichtbare Warnung (Cleartext bleibt erlaubt, s. 6.3) |

### 2.2 Zweiter Durchgang (2026-09-03) — „Abschnitt 2 vollständig abgearbeitet"

| # | Schweregrad | Befund | Stand |
|---|---|---|---|
| 2-12 | Hoch | **Symlinks auf Verzeichnisse wurden verfolgt** — beim Löschen/Kopieren/Größe-Ermitteln/Zählen: ein Lösch-Link konnte einen fremden Zielordner leeren, ein Ringlink endlos kopieren | Behoben — Symlinks werden nicht mehr verfolgt |
| 2-13 | Mittel | OVERWRITE löschte die bereits verifizierte Kopie, wenn nur noch das abschließende Umbenennen scheiterte — verlor Original *und* Kopie | Behoben |
| 2-14 | Mittel | Der Texteditor schrieb direkt in die Zieldatei — ein Absturz mitten im Schreiben hinterließ abgeschnittenen Inhalt | Behoben — temporäre Datei + Rename |
| 2-15 | Mittel | ZIP-Archivnamen und WebDAV-Downloadnamen liefen nicht durch `sanitizeName` | Behoben |
| 2-16 | Mittel | WebDAV-Upload mit nicht lesbarem Quell-Stream meldete eine leere Datei als Erfolg | Behoben |
| 2-17 | Mittel | Ein abgebrochener Kopiervorgang meldete den unvollständigen Ordner als Erfolg | Behoben |
| 2-18 | Mittel | **Doppeltes URL-Decoding der Navigations-Parameter** — Compose Navigation dekodiert bereits selbst; zusätzlich lag ein Kodierungs-Mismatch vor (`URLEncoder` vs. `Uri.decode`) | Behoben |
| 2-19 | Niedrig | Der Bildbetrachter baute seine Geschwister-Liste auf dem Main-Thread (ANR-Risiko bei sehr großen Ordnern) | Behoben — asynchron |
| 2-20 | Niedrig | WebDAV-Downloads ohne Größenbegrenzung | Behoben — 10 GiB, analog zum Zip-Bomben-Schutz |
| 2-21 | Niedrig | ZIP-Extraktion begrenzte nur die Gesamtgröße, nicht die Eintragsanzahl | Behoben — Schutz vor Viele-winzige-Einträge-Bomben |

---

## 3. ConneXias Kamera

### 3.1 Erster Durchgang (2026-09-02)

| # | Schweregrad | Befund | Stand |
|---|---|---|---|
| 3-01 | Hoch | **`FLAG_SECURE` fehlte** — Sucher und Kurz-Ansicht ließen sich per Screenshot/Bildschirmaufnahme mitschneiden | Behoben |
| 3-02 | Mittel | **Kamera blieb während der Review-Ansicht gebunden** — `releaseCamera()` lief nur bei `ON_PAUSE`, die Compose-Navigation Sucher→Review löst das nicht aus (LED bleibt an, Akku, belegte Session) | Behoben — zusätzlicher `DisposableEffect` |
| 3-03 | Mittel | Doppelauslösung: `isBusy` deckte das Fenster zwischen Auslöser-Tap und fertig geschriebenem Foto nicht ab | Behoben — `isCapturingPhoto` |
| 3-04 | Mittel | `WRITE_EXTERNAL_STORAGE` (API 26–28) deklariert, aber nie angefragt — `MediaStore.insert` scheiterte dort mit `SecurityException` | Behoben |
| 3-05 | Mittel | Filter-Speichern dekodierte in voller Auflösung und ignorierte die EXIF-Rotation (OOM-Risiko, gedrehte Ergebnisse) | Behoben — Downsampling auf max. 4096 px + Rotationskorrektur |
| 3-06 | Niedrig | Zoom-Regler fest auf 1×–8× verdrahtet, unabhängig von der Hardware | Behoben — aus `Camera.cameraInfo.zoomState` |
| 3-07 | Niedrig | Torch/EV gingen nach jedem Rebind verloren | Behoben — werden nach jedem Bind wiederhergestellt |
| 3-08 | Niedrig | Selbstauslöser nicht abbrechbar; Bildschirm konnte während der Videoaufnahme ausgehen | Behoben |
| 3-09 | Niedrig | Warden-Kamerasperre zeigte die rohe CameraX-Bind-Fehlermeldung | Behoben — `getCameraDisabled()` wird direkt abgefragt |

### 3.2 Zweiter Durchgang (2026-09-03)

| # | Schweregrad | Befund | Stand |
|---|---|---|---|
| 3-10 | Mittel | **Auslöser konnte dauerhaft tot bleiben** — `takePhoto`/`startVideoRecording` kehrten bei noch ungebundenem Use-Case still ohne Callback zurück, `isCapturingPhoto` hing bis zum nächsten `releaseCamera()` | Behoben — Fehler-Callback |
| 3-11 | Mittel | **Bind-Race ohne Generation/Cancel** — bei schnellem Modus-/Objektivwechsel gewann der zuletzt *ankommende* statt der zuletzt *angeforderte* Bind | Behoben — Generation-Zähler |
| 3-12 | Mittel | **`EXTRA_OUTPUT`-Ziel bekam ein dauerhaftes Duplikat** — die interne Kopie blieb nach der Übergabe für immer als zweites Galerie-Element stehen | Behoben — wird nach erfolgreicher Übergabe gelöscht |
| 3-13 | Mittel | Filter-Speichern konnte einen kaputten MediaStore-Eintrag hinterlassen (`IS_PENDING=1` für immer, oder leerer sichtbarer Eintrag) | Behoben — halbfertiger Eintrag wird gelöscht |
| 3-14 | Mittel | **`RECORD_AUDIO` war Pflicht für den Fotobetrieb** — eine Ablehnung sperrte den kompletten Sucher statt nur den Ton | Behoben — `required` umfasst nur noch `CAMERA`, Videomodus nimmt notfalls stumm auf |
| 3-15 | Niedrig | Teilen ohne `ClipData` | Behoben |
| 3-16 | Niedrig | Doppeltes URL-Decoding der Review-Route | Behoben |
| 3-17 | Niedrig | Bildschirm ging während Countdown/Foto-Schreiben aus (`keepScreenOn` nur an `isRecording`) | Behoben — an `state.isBusy` |

---

## 4. ConneXias Galerie

### 4.1 Erster Durchgang (2026-09-02)

| # | Schweregrad | Befund | Stand |
|---|---|---|---|
| 4-01 | Hoch | **Cloud-Sync-Lüge**: Zugangsdaten wurden schon beim Tippen auf den Button persistiert, nicht erst nach erfolgreichem Verbindungstest; ein leeres Passwort wurde akzeptiert | Behoben |
| 4-02 | Mittel | Zwei unabhängige Fotos mit gleichem Dateinamen überschrieben sich auf dem Server gegenseitig | Behoben — MediaStore-ID als Präfix |
| 4-03 | Mittel | Ein laufender Sync brach beim Wegnavigieren ab | Behoben — prozessweiter Scope (Process-Tod s. 6.2) |
| 4-04 | Mittel | `PhotoEditor` dekodierte in voller Sensorauflösung (OOM) und verwarf die EXIF-Rotation | Behoben — `inSampleSize` + Rotationskorrektur |
| 4-05 | Mittel | Der `ContentObserver` führte die MediaStore-Query blockierend auf dem Main-Thread aus (ANR bei großen Bibliotheken) | Behoben — Hintergrund-Thread |
| 4-06 | Niedrig | „Aufnahmedatum" sortierte nach `DATE_ADDED` (Import-Zeitpunkt) statt `DATE_TAKEN` | Behoben — `DATE_TAKEN` mit Fallback |
| 4-07 | Niedrig | `http://`-Server im Cloud-Sync-Formular ohne Warnung | Behoben |

### 4.2 Zweiter Durchgang (2026-09-03)

| # | Schweregrad | Befund | Stand |
|---|---|---|---|
| 4-08 | Hoch | **`ACTION_VIEW` ohne Authority-Prüfung** — jede Uri mit numerischem letztem Pfadsegment wurde akzeptiert, `content://fremde.app/item/42` hätte MediaStore-Element 42 geöffnet | Behoben — auf `MediaStore.AUTHORITY` beschränkt |
| 4-09 | Mittel | **Der System-Löschdialog behandelte Abbrechen wie Löscherfolg** (Betrachter, Grid, Papierkorb) | Behoben — `resultCode == RESULT_OK` wird geprüft |
| 4-10 | Mittel | Ein nicht lesbares Element wurde als leere Datei hochgeladen und als gesichert markiert (Server antwortet auf leeres PUT oft mit 2xx) | Behoben — gilt vor dem Upload als Fehlschlag |
| 4-11 | Mittel | „Jetzt sichern" persistierte die Formularfelder ungetestet — derselbe Fehler wie 4-01, nur am zweiten Knopf | Behoben |
| 4-12 | Mittel | `CustomAlbumScreen` rief `onNavigateUp()` im Composable-Körper statt in einem Seiteneffekt (Absturz-/Endlosschleifen-Risiko) | Behoben — `LaunchedEffect` |
| 4-13 | Mittel | Die Bild-/Video-Auswahl für fremde Apps gewährte keinen dauerhaften Zugriff (`FLAG_GRANT_PERSISTABLE_URI_PERMISSION` fehlte) | Behoben |
| 4-14 | Mittel | **Auswahl leckte über Alben hinweg** — ein frisch geöffnetes Album zeigte sofort den Auswahlmodus mit dem Stand des zuletzt verlassenen | Behoben — Reset bei echtem Bucket-Wechsel |
| 4-15 | Niedrig | Ein `displayName` mit „/" landete durch den Remote-Pfad-Splitter in einem zusätzlichen Unterordner | Behoben — saniert |
| 4-16 | Niedrig | Teilen ohne `ClipData` | Behoben |
| 4-17 | Niedrig | Der Bildeditor transkodierte verlustbehaftet neu (und verlor alle EXIF-Metadaten), auch wenn weder Filter noch Zuschnitt etwas verändert hätten | Behoben — Originalbytes werden unverändert kopiert |
| 4-18 | Niedrig | Pending-Leiche in `PhotoEditSaver.saveEdited` — derselbe Fehler wie 3-13 | Behoben |
| 4-19 | Niedrig | **Doppeltes URL-Decoding der Album-/Bucket-Namen** aus der Route, plus Encoding-Mismatch (`URLEncoder`s „+" vs. `Uri.decode`) — ein Albumname mit Leerzeichen kam als „Name+mit+Leerzeichen" an | Behoben — `Uri.encode`, App dekodiert selbst gar nicht mehr |

---

## 5. Suite-Schnittstellen

Gegenstand dieses Abschnitts sind die App-übergreifenden Intent-Verträge — nicht die Interna
einer einzelnen App. Der Befund des Durchgangs war strukturell: **die vier Apps kooperierten in
deutlich weniger Richtungen, als ihre eigenen READMEs nahelegten.** Jede App war jeweils nur für
genau die Richtung verkabelt, die im ursprünglichen Ausbauschritt gebraucht wurde.

| # | Befund | Stand |
|---|---|---|
| 5-01 | **Files war nur „Teilen"-Empfänger**, nie „Öffnen mit"-Ziel und nie Datei-Picker für andere Apps | Behoben — `ACTION_VIEW` (`data/share/IncomingView.kt`) und `ACTION_GET_CONTENT` (`data/share/PickRequest.kt`) |
| 5-02 | **Galerie war nie SEND-Empfänger** — „Teilen mit ConneXias Galerie" existierte nicht | Behoben — `ACTION_SEND`/`ACTION_SEND_MULTIPLE` über `data/media/SharedMediaImporter.kt`, reine Byte-Kopie ohne Recompress |
| 5-03 | **Kamera war nicht als Aufnahmeziel für fremde Apps ansprechbar** — jeder Aufruf landete bei der Geräte-Default-Kamera | Behoben — System-Kamera-Contract (`ACTION_IMAGE_CAPTURE`/`ACTION_VIDEO_CAPTURE`) |
| 5-04 | Es gab keine Übersicht darüber, welche App mit welcher spricht — die Kopplung war nur pro App dokumentiert | Behoben — Suite-Überblick im Root-README (`README.md`) |

Bewusst **keine** Schnittstelle geworden (unverändert gültig, s. Root-README): eine gemeinsame
WebDAV-Kontenverwaltung zwischen Files und Galerie. Sie bräuchte einen gemeinsamen
Signaturschlüssel plus `signature`-geschützten ContentProvider oder ein deutlich größeres
Vertrauensmodell zwischen vier unabhängig verteilten APKs; der Nutzen (Zugangsdaten nicht zweimal
eintippen) steht nicht im Verhältnis zur neu geschaffenen Angriffsfläche.

---

## 6. Offene Punkte und bewusst akzeptierte Risiken

### 6.1 Empirisch bestätigte Einschränkungen

- **Factory Reset Protection ist auf mindestens einem realen OEM-Gerät wirkungslos.**
  Live getestet auf Samsung SM-A156B (2026-08-25): Konto gesetzt, per `dumpsys device_policy`
  verifiziert, echter Recovery-Wipe ausgelöst — **keine Kontoabfrage**, das Gerät kam vollständig
  ungeschützt zurück. Der In-App-Warntext sagt das inzwischen explizit. Nicht als
  Diebstahlschutz behandeln, solange nicht auf der Zielhardware neu verifiziert.
- **Kamerasperre wirkt gegen gewöhnliche Apps** (2026-08-30 kausal in beide Richtungen bewiesen,
  eigenes signiertes Test-APK bekam `CameraAccessException(CAMERA_DISABLED)`). Die
  Hersteller-Kamera-App trägt vermutlich eine OEM-Ausnahme als privilegierte System-App — für das
  Bedrohungsmodell (fremde Dritt-App) irrelevant. `dumpsys device_policy` zeigt den Zustand nie an:
  ein Diagnose-Blindfleck, keine Durchsetzungslücke.
- **BLE-Tracker-Wächter kann einen echten AirTag verpassen.** Ein AirTag rotiert seine
  BLE-Kennung regelmäßig genau gegen diese Art Langzeit-Erkennung; rotierte Kennungen lassen sich
  nicht als dasselbe physische Gerät korrelieren. Ehrlich dokumentierte Heuristik, kein
  garantierter Schutz.
- **Mobilfunk-Analyse ist eine Verdachtsheuristik, keine verifizierte IMSI-Catcher-Erkennung.**
  Eine Userspace-App hat auf Android keinen Baseband-Zugriff; keiner der vier Indikatoren wurde
  je gegen eine echte Rogue-Basisstation geprüft.

### 6.2 Offen, bewusst zurückgestellt

| Punkt | App | Warum offen |
|---|---|---|
| Direct-Mode-Traffic-Test (`dig`/`curl` durch den Tunnel) | Warden | Braucht das physische Testgerät. ChildVPN ist seit 2026-09-01 end-to-end bestätigt (Handshake, Relay, Rückweg); Direct-Mode wurde nie unter demselben Maßstab geprüft |
| Echte Job-Warteschlange | Files | Mehrere Jobs sind UI-seitig gesperrt (2-08), aber es gibt keine Queue, die sie nacheinander abarbeitet |
| Process-Tod-sicherer Cloud-Sync | Galerie | Übersteht Navigation (4-03), aber nicht das Killen der App im Hintergrund — bräuchte einen `WorkManager`-Worker |
| MediaStore-Paging | Galerie | Die gesamte Bibliothek liegt im Speicher; der akute ANR-Auslöser ist behoben (4-05), Paging selbst bleibt offen |
| `GET_CONTENT` mit Mime-Typ-Filter | Files | Es wird jeder Dateityp zurückgegeben, unabhängig davon, wonach der Aufrufer fragt |

### 6.3 Akzeptierte Risiken

- **Cleartext-WebDAV bleibt erlaubt** (Files und Galerie), jetzt mit sichtbarer Warnung. Begründung
  unverändert in `network_security_config.xml`: selbst gehostete Server im Heimnetz haben oft kein
  gültiges Zertifikat, und ein hartes Verbot würde die Funktion für genau die Zielgruppe
  unbrauchbar machen, für die sie gebaut ist.
- **Die WLAN/Hotspot-Freigabe in Files ist HTTP ohne TLS**, abgesichert nur durch einen
  Token-Zwang in der URL plus Pfad-Traversal-Schutz. Reines Heimnetz-Szenario, ausdrücklich kein
  öffentliches Internet.
- **Ein exportiertes Warden-Audit-Log kann rohe GPS-Koordinaten enthalten** (aus jedem
  Anti-Diebstahl-Alarm) und landet dort, wohin der SAF-Picker zeigt — möglicherweise ein
  cloud-synchronisierter Ordner. Kein Fehler, sondern der beabsichtigte Zweck beider Funktionen;
  vor dem Teilen eines Exports wissenswert.
- **`WIPE_DATA` bleibt ein reiner Stub.** Es ist die einzige der sechs destruktiven Aktionen ohne
  Weg zurück; alle lokalen Auslöser reagieren stattdessen mit einem Neustart nach BFU.
- **Android 10 (API 29) in Files** bleibt auf app-eigene Verzeichnisse beschränkt (nach Scoped
  Storage, vor `MANAGE_EXTERNAL_STORAGE`), **API 26–28 in der Kamera** legt Aufnahmen ohne
  `RELATIVE_PATH` im Standardverzeichnis statt im eigenen Unterordner ab, und **exakt API 29 in
  der Galerie** braucht nach der Löschbestätigung einen zweiten Tap. Drei bewusst in Kauf
  genommene Randfälle für seit Jahren nicht mehr gepflegte Android-Versionen.
