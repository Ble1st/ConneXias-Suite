# ConneXias Suite

Vier eigenständige FOSS-Android-Apps, jede ein eigenes Gradle-Root-Projekt ohne gemeinsames
Modul — bewusst kein Multi-Modul-Monorepo, sondern vier unabhängige APKs, die über Android-Intents
zusammenarbeiten. Kein Google-Play-Services-/proprietärer-Google-Dienst in irgendeiner der vier
Apps; Vertrieb per GitHub-Release/Sideload statt Play Store. Apache-2.0, s. [LICENSE](LICENSE).

| App | Paket | Zweck | README |
|---|---|---|---|
| **Warden** | `de.ble1st.warden` | Device-Owner-Härtung, Sentinel-Wächter-App, ChildVPN | [warden/README.md](warden/README.md) |
| **ConneXias Files** | `de.ble1st.files` | Dateimanager (kompletter lokaler Speicher + WebDAV) | [files/README.md](files/README.md) |
| **ConneXias Kamera** | `de.ble1st.camera` | Sucher, Foto-/Videoaufnahme | [camera/README.md](camera/README.md) |
| **ConneXias Galerie** | `de.ble1st.gallery` | Alben, Betrachter, Bildeditor, Cloud-Sicherung | [gallery/README.md](gallery/README.md) |

Jede App dupliziert die für sie relevanten Muster eigenständig (Viewer-Code, WebDAV-Client,
Bildfilter, ...) statt Code über ein gemeinsames Modul zu teilen — bewusste Entscheidung, damit
jede App unabhängig baubar, testbar und veröffentlichbar bleibt, ohne dass eine Änderung in einer
App eine der anderen drei mitreißen kann. Der Preis dafür ist Duplizierung an einzelnen Stellen
(z. B. WebDAV-Client in Files und Galerie, Bildfilter in Kamera und Galerie) — akzeptiert, nicht
versehentlich liegen geblieben.

## Wie die Apps zusammenspielen

```
Kamera  --setPackage(gallery)-->  Galerie   (ACTION_VIEW auf die neue Aufnahme)
Kamera  --ACTION_IMAGE_CAPTURE-->  jede andere App kann über Kamera fotografieren
Files   <--ACTION_SEND-----------  Teilen aus jeder App landet im gewählten Ordner
Files   <--ACTION_VIEW-----------  "Öffnen mit ConneXias Files" aus jeder App
Files   <--ACTION_GET_CONTENT----  Files als Datei-Picker für jede andere App
Galerie <--ACTION_VIEW/PICK/GET--  "Öffnen mit"/Bild-Auswahl aus jeder App
Galerie <--ACTION_SEND-----------  Teilen aus jeder App landet als neues Album-Element
Warden  ------------------------>  schützt alle drei Suite-Pakete zusätzlich vor Deinstallation/Freeze
```

Jede App bleibt trotzdem für sich allein voll funktionsfähig — die Intents sind Kooperation, keine
Abhängigkeit. WebDAV-Konten (Files' Netzwerkspeicher, Galeries Cloud-Sicherung) sind bewusst pro
App getrennt konfiguriert statt über eine gemeinsame Kontenverwaltung geteilt: eine echte
Cross-App-Freigabe bräuchte entweder einen gemeinsamen Signaturschlüssel mit einer
`signature`-geschützten ContentProvider-Schnittstelle oder ein anderes, deutlich größeres
Vertrauensmodell zwischen vier unabhängig verteilten APKs — der Nutzen (Zugangsdaten nicht zweimal
eintippen) steht in keinem Verhältnis zu der damit neu geschaffenen Angriffsfläche.

## Build & Test

Jede App wird einzeln aus ihrem eigenen Ordner gebaut:

```
cd <warden|files|camera|gallery>
./gradlew test              # Unit-Tests
./gradlew :app:assembleDebug
./gradlew lint
```

CI (`.github/workflows/ci.yml`) baut und testet alle vier Apps parallel (Matrix-Job) bei jedem
Push/PR gegen `main`, plus einen separaten Job für Wardens Rust-Engine (`cargo fmt`/`clippy`/
`test`).

## Herkunft / Änderungsverlauf

`analyse.md` im Repo-Root ist das laufende Sicherheits-/Robustheits-Audit der Suite (mehrere
Durchgänge, jeweils mit Datum) — jede App-eigene README dokumentiert die daraus resultierenden
Fixes chronologisch in einem eigenen Abschnitt. Warden zusätzlich unter `warden/CLAUDE.md`
(englisch, nicht versioniert) mit einer tieferen Architekturreferenz.

## License

Apache License 2.0 — s. [LICENSE](LICENSE). Gilt für alle vier Apps gleichermaßen.
