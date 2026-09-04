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
./gradlew test                       # Unit-Tests (JVM)
./gradlew connectedDebugAndroidTest  # Instrumentation-Tests (Gerät/Emulator nötig)
./gradlew :app:assembleDebug
./gradlew lint
```

CI (`.github/workflows/ci.yml`) baut und testet alle vier Apps parallel (Matrix-Job) bei jedem
Push/PR gegen `main`, plus einen separaten Job für Wardens Rust-Engine (`cargo fmt`/`clippy`/
`test`) und einen dritten für die Instrumentation-Tests auf einem Emulator.

Der Emulator-Job deckt Files, Kamera und Galerie ab. Wardens eigene `androidTest`-Klassen laufen
dort **nicht**: sie brauchen ein per `dpm set-device-owner` provisioniertes Gerät ohne Konten,
was ein Standard-AVD-Image nicht hergibt — sie bleiben dem echten Testgerät vorbehalten.

Was in einem Instrumentation-Test gehört und was nicht: alles, was `Intent`, `Uri`,
`SharedPreferences` oder Compose anfasst, liefert unter einem reinen JVM-Unit-Test nur
`RuntimeException("Stub!")` — genau deshalb waren die App-übergreifenden Intent-Verträge lange
komplett ungetestet (s. `analyse.md` 7-01). Reine Datei-/Sortier-/Formatierungslogik bleibt
JVM-Unit-Test, weil die schneller ist.

## Release

Jede App wird **unabhängig** versioniert und veröffentlicht — vier APKs, vier Versionsstände, vier
Release-Seiten. Zwei Pipelines, beide mit den vollständigen CI-Checks als vorgeschaltetem
Quality-Gate:

| Pipeline | Apps | Tag-Schema |
|---|---|---|
| `.github/workflows/release.yml` | Warden (+ Sentinel) | `v1.2.3` |
| `.github/workflows/release-apps.yml` | Files, Kamera, Galerie | `files-v1.2.3`, `camera-v1.2.3`, `gallery-v1.2.3` |

Warden hat eine eigene Pipeline, weil dort zusätzlich die komplette Rust-Kette (Krypto-Engine für
vier ABIs frisch aus der Quelle) und die Sentinel-APK mitgebaut werden, die zwingend dasselbe
Zertifikat tragen muss. Beide Pipelines lassen sich per Git-Tag oder manuell über „Run workflow"
auslösen; im manuellen Fall berechnet die Pipeline die nächste Version aus dem letzten passenden
Tag, setzt den Tag selbst und legt einen Release-**Entwurf** an — die Liste der Commits seit dem
letzten Tag steht darin schon, der Entwurf ist die Gelegenheit, ihr eine Einordnung voranzustellen
und den Bau zu verwerfen, falls etwas nicht stimmt.

Alle vier Apps werden mit demselben Keystore signiert (Secrets `RELEASE_KEYSTORE_BASE64`,
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`). Ein lokaler
`./gradlew assembleRelease` ohne die entsprechenden Env-Vars liefert weiterhin eine unsignierte
APK — für einen lokalen Testbau ist kein Secret-Material nötig.

Erzeugung, Aufbewahrung und Wiederherstellung dieses Schlüssels: **[docs/RELEASE-SIGNING.md](docs/RELEASE-SIGNING.md)**.
Der Punkt ist bei einem Sideload-Vertrieb kein Formalismus — es gibt kein Play App Signing, das
einen Verlust auffangen könnte, und für ein Warden-Device-Owner-Gerät bedeutet ein verlorener
Schlüssel einen Werksreset.

Jede Release-Seite trägt zusätzlich `SHA256SUMS.txt` und den SHA-256-Fingerabdruck des
Signaturzertifikats; `release.yml` prüft dabei, dass Warden und Sentinel denselben tragen
(sie sind über eine `signature`-geschützte Permission gekoppelt) und bricht sonst ab. Unter den
festen Kopfteil des Release-Texts hängt GitHub automatisch die Liste der Commits seit dem
letzten Tag.

R8 ist nur bei Warden aktiv. Für die drei Compose-Apps bleibt es bewusst aus, solange kein
Gerätetest gegen eine minifizierte Release-APK laufen kann — die Begründung steht in der jeweiligen
`app/build.gradle.kts`.

## Installation und Updates

Sideload, kein Play Store. APK von der [Releases-Seite](https://github.com/Ble1st/ConneXias-Suite/releases)
laden, im Dateimanager öffnen, Installation aus unbekannter Quelle einmalig erlauben.

Vor dem Installieren prüfen — das ersetzt die Herkunftsbestätigung, die sonst der Store leistet:

```
sha256sum -c SHA256SUMS.txt          # Datei unverändert?
keytool -printcert -jarfile *.apk    # von wem signiert?
```

Der Zertifikat-Fingerabdruck ist für alle vier Apps derselbe und steht auf jeder Release-Seite.
Eine APK mit anderem Fingerabdruck kann eine installierte App nicht aktualisieren — Android
verweigert das. Ein „Update", das anders signiert ist, ist deshalb nie eines.

**Keine der vier Apps prüft selbst auf Updates.** Das wäre eine regelmäßige, vom Nutzer nicht
ausgelöste Verbindung nach außen; die Kamera hat aus demselben Grund gar keine
`INTERNET`-Berechtigung, und bei einer Device-Owner-App wäre es genau der Hintergrundverkehr, den
sie sonst überwacht. Stattdessen zeigt der Über-Bildschirm jeder App (bei Warden:
Einstellungen → Updates) die installierte Version samt Versionscode und verlinkt die
Releases-Seite, geöffnet vom Browser des Geräts.

## Datenschutz und Sicherheit

- **[PRIVACY.md](PRIVACY.md)** — was jede der vier Apps lokal speichert, wohin überhaupt etwas
  übertragen wird (nur an Server, die Sie selbst eintragen), und welche Berechtigung wofür da ist.
- **[SECURITY.md](SECURITY.md)** — wie eine Sicherheitslücke gemeldet wird (nicht als
  öffentliches Issue), was im Geltungsbereich liegt und was nicht.

## Herkunft / Änderungsverlauf

`analyse.md` im Repo-Root ist das laufende Sicherheits-/Robustheits-Audit der Suite (mehrere
Durchgänge, jeweils mit Datum) — jede App-eigene README dokumentiert die daraus resultierenden
Fixes chronologisch in einem eigenen Abschnitt. Abschnitt 7 dort führt zusätzlich die
Produktionsreife: was zwischen „der Code ist geprüft" und „läuft auf echten Geräten" liegt,
einschließlich der Punkte, die noch offen sind. Warden zusätzlich unter `warden/CLAUDE.md`
(englisch, nicht versioniert) mit einer tieferen Architekturreferenz.

## License

Apache License 2.0 — s. [LICENSE](LICENSE). Gilt für alle vier Apps gleichermaßen.
