# ConneXias Suite — Sicherheits-/Robustheits-Audit

Laufendes Audit über alle vier Apps der Suite (Warden, ConneXias Files, ConneXias Kamera,
ConneXias Galerie). Jeder Befund steht hier mit Schweregrad, Fundort und Auflösungsstand; die
App-eigenen READMEs führen dieselben Befunde noch einmal chronologisch als Changelog, aus Sicht
der jeweiligen App.

**Zum Stand dieses Dokuments (2026-09-04):** Abschnitt 6.2 war bis dahin veraltet — drei der
fünf dort als offen geführten Punkte waren in den Commits `09a8520`/`0339405` bereits erledigt,
ohne dass das Dokument nachgezogen worden wäre. Abschnitt 6.2 ist entsprechend korrigiert, und
Abschnitt 7 kommt neu dazu: er führt die Produktionsreife-Arbeiten, die keine Sicherheitsbefunde
sind (Instrumentation-Tests, Auslieferungsweg, Projektunterlagen), aber vor einem echten Einsatz
genauso anstehen.

**Zum ursprünglichen Stand dieses Dokuments (2026-09-03):** Die Arbeitsfassungen der einzelnen Durchgänge waren
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
| 7 | Produktionsreife (kein Sicherheitsbefund, aber Voraussetzung für den Einsatz) |

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

### 3.3 Nachzügler aus dem Routen-Review (2026-09-04)

| # | Schweregrad | Befund | Stand |
|---|---|---|---|
| 3-18 | Niedrig | **Encoding-Mismatch in `Routes.encode` war als einzige der drei Apps noch offen.** 3-16 hatte das doppelte Decodieren beseitigt, aber weiter mit `URLEncoder.encode` kodiert — das schreibt ein Leerzeichen als „+", während Compose Navigation mit `Uri.decode` dekodiert und „+" unverändert stehen lässt. Files (2-18) und Galerie (4-19) hatten genau das mitbehoben, die Kamera nicht. Latent, weil die von `MediaStoreSaver` erzeugten `content://media/...`-Uris numerisch sind — der Zeichensatz einer Uri liegt aber nicht in der Hand dieser App | Behoben — `Uri.encode`, abgesichert durch `nav/RoutesInstrumentedTest.kt` (s. Abschnitt 7.1) |

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

Aktuell leer — der einzige verbliebene Punkt (Direct-Mode-Traffic-Test) wurde 2026-09-04 erledigt,
s. Tabelle unten.

**Seit der letzten Fassung erledigt** (die Tabelle oben führte sie bis 2026-09-04 weiter als
offen, obwohl sie es nicht mehr waren):

| Punkt | App | Erledigt in |
|---|---|---|
| Echte Job-Warteschlange | Files | `09a8520` — `data/fileops/FileOperationQueue.kt`, abgearbeitet vom `FileOperationService` |
| `GET_CONTENT` mit Mime-Typ-Filter | Files | `09a8520` — `data/share/PickRequest.kt` + `MimeTypeFilter.kt`, seit 2026-09-04 durch `PickRequestInstrumentedTest` abgesichert |
| Process-Tod-sicherer Cloud-Sync | Galerie | `0339405` — `data/sync/CloudSyncWorker.kt` als WorkManager-Foreground-Auftrag |
| MediaStore-Paging | Galerie | 2026-09-04 — s. 7-12 |
| Direct-Mode-Traffic-Test (`dig`/`curl` durch den Tunnel) | Warden | 2026-09-04, am physischen Testgerät — ICMP/TCP funktionierten sofort, UDP/DNS zunächst nicht. Zwei echte, bis dahin unentdeckte Bugs in `engine.rs` gefunden und behoben: (1) das erste Datagramm eines neuen UDP-Flows wurde verworfen, bevor der externe `protect()`-Socket überhaupt bereit war — für TCP unschädlich (das SYN trägt keine Nutzlast), für eine einzelne DNS-Anfrage tödlich; gepuffert und nachgereicht, sobald der Socket steht. (2) UDP-Antworten trugen die falsche Quelladresse (`10.64.0.1`, die TUN-eigene, statt der tatsächlich angefragten Serveradresse) — `send_slice` ohne explizite `local_address` lässt smoltcp die Quelladresse über die Interface-Routingtabelle raten, die bei nur einer konfigurierten Adresse immer diese liefert; ein verbundenes Client-UDP-Socket verwirft eine Antwort mit unerwarteter Quelladresse lautlos im Kernel. Beide Bugs live per temporärer `__android_log_write`-Instrumentierung bestätigt (danach entfernt), s. `warden/CLAUDE.md`-Abschnitt „Netz-Sperre" für die volle Analyse. Direct-Mode ist damit wie ChildVPN als Ende-zu-Ende bestätigt funktionsfähig (ICMP/TCP/UDP) einzustufen |

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

---

## 7. Produktionsreife

Dieser Abschnitt führt kein Sicherheitsaudit fort. Er hält fest, was zwischen „der Code ist
geprüft" und „die Apps laufen auf echten Geräten" liegt — Punkte, die keine Befunde im Sinne der
Abschnitte 1–5 sind, aber vor einem Einsatz genauso erledigt sein müssen. Aufgenommen am
2026-09-04, nachdem eine Bestandsaufnahme über die ganze Suite gelaufen war.

### 7.1 Erledigt (2026-09-04)

| # | Gegenstand | Was fehlte, und was jetzt da ist |
|---|---|---|
| 7-01 | **Instrumentation-Tests für Files/Kamera/Galerie** | Warden hatte 14 `androidTest`-Klassen, die drei Compose-Apps **null**. Damit war genau die Schicht ungetestet, die ein reiner JVM-Unit-Test nicht erreicht: alles, was `Intent`, `Uri` oder `SharedPreferences` anfasst, liefert dort nur `RuntimeException("Stub!")`. Sämtliche Befunde der Abschnitte 2–5 wurden per Code-Review gefunden, nicht von einem Test — gegen eine Regression stand nichts. Jetzt neun Testklassen über die drei Apps: Routen-Encoding (2-18/3-16/3-18/4-19), `ACTION_GET_CONTENT`-Vertrag samt Typfilter, `ACTION_SEND`/`ACTION_VIEW`-Empfang, `ExternalIntent`-Authority-Prüfung (4-08), Kamera-Einstellungen samt Fallback bei unbekannten gespeicherten Werten, Favoriten-Speicherformat, und die beiden Compose-Dialoge, an denen Daten verloren gehen (Papierkorb-Bestätigung, Konfliktauflösung) |
| 7-02 | **CI ließ `androidTest` nie laufen** | Auch Wardens vorhandene Tests liefen in keiner Pipeline. Neuer Job `instrumented` in `ci.yml`: Emulator-Matrix über die drei Compose-Apps. Warden bleibt bewusst außen vor — seine Tests brauchen ein per `dpm set-device-owner` provisioniertes Gerät, das ein Standard-AVD nicht hergibt |
| 7-03 | **Zwei Befunde brauchten erst eine testbare Struktur** | Die Authority-Prüfung aus 4-08 lag als privater Block in `MainActivity` und war nur über einen echten Activity-Start erreichbar, also praktisch gar nicht. Sie liegt jetzt in `ExternalIntent.from(intent, resolveMimeType)` — reine Funktion, kein `Context`. Verhalten unverändert |
| 7-04 | **56 hartkodierte UI-Texte in Files** | Galerie (0), Kamera (1) und Warden (3) waren sauber, Files hatte 56 Literale gegen 68 `stringResource`. Alle in `res/values/strings.xml` überführt. Nebenbei fiel eine Kopplung auf, die eine Übersetzung lautlos kaputtgemacht hätte: `HomeScreen` wählte das Icon eines Speicherorts über den deutschen Anzeigetext (`when (label) { "Bilder" -> ... }`). `StorageRoot`/`QuickAccessFolder` tragen jetzt eine `Kind`-Aufzählung, die Beschriftung kommt aus den Ressourcen |
| 7-05 | **Keine Verifizierbarkeit einer APK** | Sideload ohne Store heißt: keine Instanz bestätigt dem Nutzer die Herkunft. Beide Release-Pipelines erzeugen jetzt `SHA256SUMS.txt` und schreiben den SHA-256-Fingerabdruck des Signaturzertifikats in den Release-Text. `release.yml` prüft zusätzlich, dass Warden und Sentinel denselben Fingerabdruck tragen, und bricht sonst ab — die beiden sind über eine `signature`-geschützte Permission gekoppelt, ein ungleiches Paar wäre auf dem Gerät funktionsunfähig |
| 7-06 | **Kein Update-Weg** | Niemand erfuhr von einer neuen Version. Eine In-App-Prüfung wurde **verworfen**: sie bräuchte eine regelmäßige, vom Nutzer nicht ausgelöste Verbindung nach außen — die Kamera hat aus gutem Grund gar keine `INTERNET`-Berechtigung, und bei einer Device-Owner-App wäre es genau der Hintergrundverkehr, den sie sonst überwacht. Stattdessen: Über-Bildschirm bzw. Warden-Einstellungen zeigen Version **und Versionscode** und verlinken die Releases-Seite per `ACTION_VIEW` an den Browser. Die Release-Seiten tragen seit 7-05 alles Nötige |
| 7-07 | **Keine Release-Notizen** | Die Pipelines legten Entwürfe an, „damit ein Changelog eingetragen werden kann" — es gab aber keine Quelle dafür. Jetzt `generate_release_notes: true` zusätzlich zum festen Kopfteil. Bewusst keine handgepflegte `CHANGELOG.md`: die App-READMEs führen die inhaltliche Historie ohnehin, nach Befund statt nach Release; eine dritte Liste wäre die erste, die veraltet |
| 7-08 | **Projektunterlagen unvollständig** | `SECURITY.md` lag unter `warden/` — galt formal nur für eine App und war für GitHubs Weboberfläche unsichtbar (die liest nur Root, `.github/`, `docs/`). Jetzt im Root und für alle vier Apps formuliert, mit Verweis auf die Release-Verifikation. Neu dazu: `PRIVACY.md` (Datenschutzerklärung je App — für F-Droid und jeden Sideload-Vertrieb erwartet, und der Ort, an dem der GPS-Hinweis aus 6.3 für Nutzer steht), Issue-/PR-Vorlagen, `dependabot.yml` über fünf Ökosysteme |
| 7-09 | **Kein Verfahren für den Signaturschlüssel** | Ein Keystore für vier Apps, nur als GitHub-Secret — und GitHub gibt ein gesetztes Secret nicht wieder heraus. Verlust hieß: keine Updates mehr für vier installierte Apps, und für ein Warden-Device-Owner-Gerät ein Werksreset, weil sich die App gegen genau das Deinstallieren wehrt, das eine Neuinstallation bräuchte. `docs/RELEASE-SIGNING.md` beschreibt Erzeugung, Drei-Orte-Aufbewahrung, Wiederherstellung und den Kompromittierungsfall. Repo-weites `.gitignore` sperrt Keystore-Dateien als zweite Verteidigungslinie |
| 7-10 | **Symbole ohne Beschreibung für Vorlesehilfen** | 40 × `contentDescription = null` über die vier Apps. Ein großer Teil davon ist zu Recht dekorativ (ein Symbol neben beschriftetem Text doppelt nur die Ansage), ein Drittel war es nicht: die Auswahlhäkchen in Files' Liste und Kachelansicht, in Galeries Raster, Album- und Papierkorbansicht waren die **einzige** Anzeige dafür, ob ein Element ausgewählt ist — und der leere Kreis für „nicht ausgewählt" hätte auch mit Beschreibung nichts vorzulesen gehabt. Der Zustand hängt jetzt als Standard-Semantik `selected` am Zeilen-/Kachel-Knoten, die eine Vorlesehilfe in ihrer eigenen Sprache ansagt. Beschreibungen bekommen haben außerdem: Files' Kategoriesymbol (Ordner oder Datei — der Dateiname sagt es nicht, beide können endungslos sein), lokal wie über WebDAV, Galeries Video- und Favoritenmarkierung im Raster, das Vollbild im Betrachter (Dateiname) und die Editor-Vorschau, das aufgenommene Foto in der Kamera-Nachschau und der aktive Extension-Modus in deren Menü. Dabei fiel ein echter Fehler auf: Files' Papierkorb hatte einen **völlig unbeschrifteten Zurück-Knopf** |
| 7-11 | **Abhängigkeits-Rückstand** | Dependabot fängt ab jetzt Neues ab (7-08), den bestehenden Rückstand räumte es nicht. Erledigt: AGP 9.3.2 → 9.4.0 und Kotlin 2.3.20 → 2.4.10 in allen vier Apps (die beiden hängen zusammen — seit AGP 9 kommt der Kotlin-Compiler fest eingebaut mit, die Version im Katalog steuert nur noch das Compose-Plugin und muss zur eingebauten passen), Wardens `appcompat` 1.7.1 → 1.8.0 und Compose-BOM 2026.06.01 → 2026.08.00 (damit auf demselben Stand wie die anderen drei), Files' `zxing:core` 3.5.3 → 3.5.4, `exifinterface` 1.4.1 → 1.4.2 in Kamera und Galerie, CameraX 1.5.1 → 1.6.2. Letzteres brachte einen Bruch mit: `Camera2CameraControl` ist nach Kotlin portiert, `setCaptureRequestOptions()` liefert jetzt ein `ListenableFuture` und ist damit kein Property-Setter mehr — die manuelle ISO-/Belichtungszeit-Steuerung kompilierte nicht mehr und wurde angepasst. Danach melden alle vier Lint-Läufe keine `GradleDependency`/`AndroidGradlePluginVersion`/`NewerVersionAvailable`-Warnung mehr; Wardens Release-Bau mit R8 wurde gegen die neue Werkzeugkette geprüft |
| 7-12 | **Galerie hielt die ganze Mediathek im Speicher** | Der letzte offene Punkt aus 6.2. `GalleryViewModel` hielt den vollständigen Medienbestand als `StateFlow<List<MediaItem>>`; Ordnerübersicht, Album-Inhalt, Suche, Sortierung, Auswahl und die Wisch-Geschwister des Betrachters entstanden daraus per Kotlin-Filter. Bei einer großen Bibliothek bedeutete das jedes `MediaItem` samt `Uri` und drei Zeichenketten dauerhaft im Speicher — **und** einen vollständigen Neuaufbau bei jeder einzelnen MediaStore-Änderung (der `ContentObserver` feuert pro Schreibvorgang, bei einer Serienaufnahme also im Sekundentakt). Jetzt lädt jede Ansicht nur, was sie zeigt: das Raster seitenweise über eine `PagingSource` (Fenster von 100), die Ordnerübersicht als Faltung von vier Cursor-Spalten ohne ein einziges `MediaItem`, ID-Mengen (Favoriten, eigene Alben) gezielt, ein einzelnes Element einzeln. Das Änderungssignal ist auf 300 ms zusammengefasst. Zwei Dinge mussten dabei von Kotlin nach SQL wandern: die **Suche** (`DISPLAY_NAME LIKE` mit maskierten Platzhaltern — clientseitig hätte sie nur die schon gescrollten Seiten durchsucht) und die **Sortierung**. Letztere ist der heikle Teil: das angezeigte Datum ist `DATE_TAKEN` mit `DATE_ADDED`-Rückfall, in SQL also ein `CASE WHEN`-Ausdruck, den der MediaProvider (`SQLiteQueryBuilder.setStrict`) ablehnen darf. Deshalb eine Ersatzsortierung aus reinen Spaltennamen, auf die bei einer Ablehnung automatisch zurückgefallen wird, plus `MediaQueryInstrumentedTest`, der auf dem Emulator feststellt, ob der Rückfall überhaupt gebraucht wird |
| 7-13 | **Alle vier App-Icons waren Platzhalter** | Warden trug unverändert das Android-Studio-Vorlagensymbol (der grüne Roboter auf `#3DDC84`-Raster), Files, Kamera und Galerie teilten sich **dieselbe** Ordner-Grafik — im XML selbst als „Platzhalter, kein finales App-Icon" vermerkt —, unterschieden nur durch die Hintergrundfarbe. Kamera und Galerie standen damit als Ordner im Launcher. Bei einem Sideload-Vertrieb ohne Store-Eintrag ist das Symbol die einzige Wiedererkennung. Jetzt vier eigene Adaptive Icons: Schild mit ausgespartem Haken auf Terminal-Schwarz (Warden), Ordner mit Dokumentzeilen (Files), Gehäuse mit Linse und Blitz (Kamera), Bildrahmen mit Sonne und Bergsilhouette (Galerie) — jeweils ein einziger Pfad mit `fillType="evenOdd"`, damit dieselbe Grafik auch die monochrome Ebene (themed icons ab Android 13) trägt, und vollständig innerhalb des 66dp-Sicherheitskreises. Wardens zehn WebP-Fallbacks für API < 26 sind entfallen: bei minSdk 35 unerreichbar, hätten aber den Roboter weiter in der APK mitgeschleppt |
| 7-17 | **Verschlüsselte Zugangsdaten hingen an einer eingestellten Bibliothek** | Files und Galerie legten ihre WebDAV-Passwörter in `EncryptedSharedPreferences` ab (`androidx.security:security-crypto` 1.1.0, mit `com.google.crypto.tink:tink-android` im Schlepptau). Jetpack Security ist von Google eingestellt: die Klassen sind als veraltet markiert, ohne benannten Nachfolger, und jeder Übersetzungslauf beider Apps meldete das neunmal. Eine eingestellte Krypto-Bibliothek ist kein Zustand, in dem man in Produktion geht — sie bekommt weder neue Android-Fassungen noch Sicherheitskorrekturen. Ersetzt durch `data/crypto/SecretStore.kt` (in beiden Apps eigenständig, wie im Rest der Suite kein geteilter Code): AES-256/GCM, Schlüssel im Android-Keystore erzeugt und nicht exportierbar, Initialisierungsvektor mit Längenangabe vor dem Geheimtext, alles Base64 in gewöhnlichen `SharedPreferences`. Zwei bewusste Abweichungen vom Abgelösten: die **Schlüsselnamen** bleiben im Klartext (sie sind fest verdrahtete Konstanten wie `password` und verraten nichts, was nicht im Quelltext steht), und ein **unlesbarer Wert liefert `null` statt einer Ausnahme** — `EncryptedSharedPreferences` warf dort bis in den Aufrufer, obwohl „Konto nicht eingerichtet" die brauchbare Antwort ist. Keine Datenübernahme: beide Apps sind unveröffentlicht, eine Migration hieße die veraltete Bibliothek genau dafür weiter mitzuschleppen. Abgesichert durch `SecretStoreTest` (je neun Instrumentation-Tests, Keystore gibt es nur auf dem Gerät): Rundreise samt Umlauten und Emoji, Wert steht nicht im Klartext in der Datei, zweimal derselbe Klartext ergibt verschiedene Geheimtexte, unlesbarer Eintrag ergibt `null`, ein frisch gebauter Store liest das vom vorherigen Geschriebene |
| 7-14 | **Keine Release-APK lief je auf Hardware** | 2026-09-04 am physischen Testgerät nachgeholt: Warden+Sentinel mit einem Wegwerf-Test-Keystore signiert gebaut (`assembleRelease`, R8+Ressourcen-Shrinking aktiv), Zertifikat-Fingerabdrücke beider APKs verglichen (identisch, Warden↔Sentinel-Kopplung intakt), auf dem Gerät installiert und als Device Owner provisioniert. Durchgetestet: PIN setzen (Argon2-Hash + Envelope-Verschlüsselung über den AndroidKeystore-KEK, komplett über die UniFFI-Rust-Grenze) und PIN-Verify, dazu ein echter Safeguard-Toggle (`no_factory_reset`-Restriktion über den DevicePolicyManager) — alles ohne Absturz, kein `UnsatisfiedLinkError`, keine `FATAL EXCEPTION` im Logcat. Die R8-Keep-Regeln unter `warden/app/src/release/keepRules/` halten damit in der Praxis. Dabei ein bis dahin unbekannter operativer Fund (nicht code-, sondern Android-plattformseitig): ein Device Owner, der **nicht** `testOnly` ist (jeder normale Release-Build), lässt sich per `dpm remove-active-admin` grundsätzlich nicht von der Rolle lösen (`SecurityException: Attempt to remove non-test admin`) — Android verweigert das als Schutz vor Enterprise-Policy-Verlust, und Warden bietet auch keinen In-App-`clearDeviceOwnerApp()`-Weg. Ein mit dem Test-Keystore signiertes Warden-Release-Gerät lässt sich also **nur per vollständigem Werksreset** wieder auf einen Debug-Build umstellen — nicht nur „PIN/Audit-Log/ChildVPN neu anlegen" wie vor dem Test angenommen. Für den Testablauf hieß das: Werksreset des Testgeräts, danach Debug-Build neu installiert und erneut als Device Owner provisioniert. **Relevant für 7-16/die Provisionierungs-Doku**: derselbe Mechanismus gilt für jedes real ausgerollte Warden-Gerät — ein Rückzug von Device Owner ist dort ebenso nur per Werksreset möglich, das gehört in die Nutzer-Doku, nicht nur ins Testprotokoll |

### 7.2 Offen — braucht das physische Testgerät

7-14 (s. 7.1) ist erledigt und damit deren Voraussetzung erfüllt; beide folgenden Punkte sind
jetzt nicht mehr durch einen fehlenden Gerätetest blockiert, nur noch nicht selbst umgesetzt.

| # | Gegenstand | Warum offen |
|---|---|---|
| 7-15 | **R8 bei Files/Kamera/Galerie aus** | Dokumentiert und begründet („solange kein Gerätetest gegen eine minifizierte Release-APK laufen kann") — diese Voraussetzung ist mit 7-14 jetzt erfüllt (dieselben R8/Keep-Rules-Mechanismen liefen dort fehlerfrei durch), die eigentliche Umstellung für Files/Kamera/Galerie steht aber noch aus. Die Kamera-Release-APK ist dadurch weiterhin 18 MB |
| 7-16 | **Warden-Provisionierung nicht ausrollbar dokumentiert** | `warden/README.md` nennt `adb dpm set-device-owner` und „alternativ QR-Provisionierung" — es gibt aber keine QR-JSON, keinen `PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` und keine gehostete Download-URL. Für das Aufsetzen eines echten Geräts ist genau das der Weg. Der Checksum lässt sich jetzt aus einer mit dem echten Produktionsschlüssel signierten Release-APK bilden (7-14 hat den Weg dorthin verifiziert). Sollte außerdem den 7-14-Fund aufnehmen: ein Rückzug von Device Owner ist auf einem so provisionierten Gerät nur per Werksreset möglich, nicht per ADB |
