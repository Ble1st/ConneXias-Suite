# ConneXias Files

FOSS-Dateimanager (`de.ble1st.files`), Android, ein einziges Gradle-Modul. Kein
Google-Play-Services-/proprietärer-Google-Dienst verbaut; Material 3 (mit einer
expressive-nahen Formsprache, s. `ui/theme/Theme.kt`), Jetpack Compose.

## Umfang (Stand: 2026-09-03)

- Datei-Browser über den kompletten lokalen Speicher (intern + SD-Karten), via
  `MANAGE_EXTERNAL_STORAGE`.
- Alle Kern-Dateioperationen: Kopieren, Verschieben, Löschen, Umbenennen, Neuer
  Ordner/Neue Datei, Komprimieren/Entpacken (ZIP), Import per SAF-Picker, Teilen/
  "Öffnen mit" über `FileProvider`.
- Konflikt-Dialog beim Einfügen auf bereits vorhandene Namen (Überschreiben/
  Überspringen/Beide behalten), s. `data/fileops/FileOperations.kt` (`ConflictPolicy`).
- Lang laufende Vorgänge (Kopieren/Verschieben/Zip) als Foreground-Service mit
  Fortschritts-Benachrichtigung und Abbrechen-Aktion.
- Eigenschaften-Dialog (Größe, Pfad, Elementanzahl), Sortierung (Name/Datum/
  Größe/Typ), Mehrfachauswahl, Suche innerhalb des aktuellen Ordners.
- Eigener Bildbetrachter (`ui/viewer/ImageViewerScreen.kt`): Wischen zwischen allen
  Bildern desselben Ordners, Pinch-/Doppeltipp-Zoom, Drehen der Ansicht — via Coil.
- Eigener Videoplayer (`ui/viewer/VideoPlayerScreen.kt`): Media3 ExoPlayer, komplett
  lokale Wiedergabe ohne Play-Services-Abhängigkeit.
- Eigener Texteditor (`ui/viewer/TextEditorScreen.kt`): Lesen, Bearbeiten, Speichern
  von Textdateien bis 2 MB, mit Verwerfen-Rückfrage bei ungespeicherten Änderungen.
- Netzwerkspeicher per WebDAV (`data/webdav/`, `ui/webdav/`): eigener OkHttp-Client
  (PROPFIND/GET/PUT/DELETE/MKCOL/MOVE, Basic Auth), beliebig viele Server-Konten
  mit verschlüsselt gespeicherten Zugangsdaten (`data/crypto/SecretStore.kt`,
  AES-256/GCM mit Schlüssel aus dem Android-Keystore).
  Durchsuchen, Herunterladen, Hochladen (SAF-Picker), Umbenennen, Löschen, Neuer
  Ordner. Bild-/Video-Dateien werden in einen Cache-Ordner geladen und im
  vorhandenen Bild-/Videobetrachter geöffnet; alle anderen Dateitypen werden
  direkt nach Downloads geladen (kein Inline-Editor für Server-Dateien, um ein
  trügerisches "gespeichert = synchronisiert" zu vermeiden).
- Empfängt "Teilen mit ConneXias Files" von fremden Apps (`data/share/IncomingShare.kt`,
  `ACTION_SEND`/`ACTION_SEND_MULTIPLE`): Home zeigt einen Hinweis-Banner, der nächste geöffnete
  Ordner wird zum Zielordner und importiert die geteilten Dateien automatisch dorthin.
- Papierkorb (2026-09-03, `data/trash/`, `ui/trash/TrashScreen.kt`): "Löschen" im Datei-Browser
  verschiebt jetzt in einen pro Speichervolume angelegten `.crx-trash`-Ordner statt sofort endgültig
  zu löschen (`FileOperations.moveToTrash`), erreichbar über das neue Papierkorb-Symbol auf Home.
  Wiederherstellen setzt den ursprünglichen Namen und Ordner wieder ein (legt ihn bei Bedarf neu an),
  "Endgültig löschen"/"Papierkorb leeren" nutzen denselben rekursiven Lösch-Pfad wie überall sonst.
  Automatische endgültige Löschung nach 30 Tagen, opportunistisch beim Öffnen des Bildschirms statt
  über einen eigenen periodischen Worker (`TrashStore.purgeExpired`).
- Grid-Ansicht (2026-09-03, `ui/browser/FileEntryGrid.kt`): Umschalter im Datei-Browser, global
  persistiert (`ViewModePreference`). Miniaturbilder über Coil für Bilder und über
  `MediaMetadataRetriever` plus einen kleinen LRU-Speichercache für Videos (`util/VideoThumbnails.kt`
  — bewusst ohne die Zusatzabhängigkeit `coil-video`, s. dortiges Klassendoc). Kein eigenes
  Pro-Kachel-Menü: Öffnen/Auswählen wie in der Listenansicht, Aktionen laufen über die vorhandene
  Auswahl-Toolbar.
- Zuletzt verwendet (2026-09-03, `data/recent/RecentFilesStore.kt`): neuer Home-Bereich mit den
  zuletzt tatsächlich geöffneten Dateien (nicht Ordnern), protokolliert am zentralen Tap-Dispatch
  in `FilesNavHost.kt`. Tap öffnet über denselben Betrachter-Pfad wie im Browser; eine
  zwischenzeitlich gelöschte oder verschobene Datei fällt beim nächsten Laden still aus der Liste.
- WLAN/Hotspot-Freigabe (2026-09-03, `data/localshare/`, `ui/localshare/LocalShareScreen.kt`): der
  aktuell geöffnete Ordner lässt sich über einen selbstgeschriebenen, minimalen HTTP-Server
  (`LocalHttpServer`, kein `nanohttpd` — seit 2016 unmaintained, s. dortiges Klassendoc) für jeden im
  selben WLAN/Hotspot per Link freigeben; erreichbar über das neue Netzwerk-Symbol im Datei-Browser.
  Token-Pflicht in der URL (kein offener Server), Pfad-Traversal-Schutz gegen Anfragen von außen.
  Der Link steht als Text (Kopieren/Teilen) und als QR-Code zum Abscannen bereit
  (`util/QrCode.kt`, `com.google.zxing:core`). Weiterhin bewusst schlank: nur Herunterladen (kein
  Upload-Empfang), kein HTTPS (reines Heimnetz-Szenario, kein öffentliches Internet).
- Rekursive Suche (2026-09-03, `data/search/RecursiveSearch.kt`): die Lupe durchsucht auf Wunsch
  den gesamten Teilbaum unterhalb des aktuellen Ordners statt nur die geladene Liste. Umschalter in
  der Suchleiste, Breitensuche (Treffer nahe am Startordner zuerst), 300-ms-Debounce, harte Grenzen
  für Trefferanzahl/besuchte Ordner/Tiefe, keine Verfolgung von Verzeichnis-Symlinks. Suchergebnisse
  zeigen den Ordner des Treffers als Unterzeile.
- Auftrags-Warteschlange (2026-09-03, `data/fileops/FileOperationService.kt`): mehrere Kopier-/
  Verschiebe-/Lösch-/Zip-Aufträge werden der Reihe nach abgearbeitet, statt dass ein zweiter
  abgewiesen wird. Die UI sperrt dadurch keine Aktionen mehr; Fortschrittsbalken und Notification
  zeigen, wie viele Aufträge noch warten. Abbrechen stoppt den laufenden Auftrag und verwirft die
  Warteschlange (s. `FileOperationQueue.requestCancel`).
- Über-Bildschirm (2026-09-03, `ui/about/AboutScreen.kt`): Version, Lizenz und Fremdbibliotheken,
  erreichbar über das Info-Symbol auf Home. Der Lizenztext liegt als
  `res/raw/third_party_licenses.txt` in der App selbst — Apache-2.0 §4(d) verlangt, dass die
  Attribution dem Empfänger der Binary zugänglich ist, nicht nur dem Leser des Repositories.

**Sicherheits-/Robustheitsfixes (2026-09-02, s. `analyse.md`):** `file_paths.xml` deckt nur noch
`/storage` statt des echten Filesystem-Roots ab; Namen bei Neu-Anlegen/Umbenennen/SAF-Import/
WebDAV-Upload werden auf reine Dateinamen reduziert (kein `../`-Path-Traversal mehr über einen
präparierten Namen oder eine fremde `DISPLAY_NAME`-Spalte); OVERWRITE beim Kopieren/Verschieben
löscht das Ziel erst nach vollständig erfolgreicher Kopie statt vorher; Kopieren/Verschieben eines
Ordners in sich selbst wird erkannt und abgelehnt statt endlos zu wachsen; WebDAV-`href` wird auch
als vollständige URL (Nextcloud) korrekt geparst, MOVE setzt `Overwrite: F` statt sich auf den
RFC-Default (stilles Überschreiben) zu verlassen; Zip-Extraktion begrenzt die entpackte
Gesamtgröße (Zip-Bomben-Schutz, ergänzt den bereits vorhandenen Zip-Slip-Schutz); Mehrfach-Teilen
setzt zusätzlich `ClipData`, damit Empfänger wie Gmail alle Uris statt nur der ersten sehen; ein
zweiter Kopier-/Verschiebe-/Lösch-/Zip-Job wird nicht mehr still verworfen, sondern die
entsprechenden Aktionen waren zunächst gesperrt, solange bereits einer lief (inzwischen durch die
echte Warteschlange oben abgelöst); "Entpacken" wird nur noch für
tatsächlich unterstützte Formate (ZIP/JAR) angeboten, nicht mehr für RAR/7z/TAR, die dort nur mit
einem kryptischen Fehler gescheitert wären; `POST_NOTIFICATIONS` wird jetzt tatsächlich angefragt
(vorher nur im Manifest deklariert); ein `http://`-Server zeigt jetzt eine sichtbare Warnung, dass
Zugangsdaten unverschlüsselt übertragen werden (Cleartext bleibt erlaubt, s.
`network_security_config.xml`-Kommentar — Begründung unverändert).

**Sicherheits-/Robustheitsfixes, 2. Durchgang (2026-09-03, s. `analyse.md`):** Symlinks auf
Verzeichnisse werden beim Löschen/Kopieren/Größe-Ermitteln/Zählen nicht mehr verfolgt (verhinderte
sowohl versehentliches Leeren eines fremden Zielordners über einen Lösch-Link als auch
endloses/riesiges Kopieren über einen Ringlink); OVERWRITE löscht die frisch verifizierte
Kopie nicht mehr, wenn nur noch das abschließende Umbenennen fehlschlägt (vorheriges Verhalten
verlor bei diesem seltenen Fehlerfall sowohl Original als auch Kopie); ZIP-Archivnamen und
WebDAV-Downloadnamen laufen jetzt ebenfalls durch `sanitizeName`; Compose-Navigation dekodierte
Pfad-Parameter doppelt (intern bereits durch Navigation selbst, zusätzlich noch einmal in der
App) — behoben, dabei auch ein latenter Kodierungs-Mismatch (`URLEncoder` vs. `Uri.decode`)
mitkorrigiert; der Texteditor schreibt jetzt atomar über eine temporäre Datei plus Rename statt
direkt in die Zieldatei (kein abgeschnittener Dateiinhalt mehr bei einem Absturz mitten im
Schreiben); der Bildbetrachter baut seine Geschwister-Liste asynchron statt auf dem Hauptthread
(kein ANR-Risiko mehr bei sehr großen Ordnern); WebDAV-Downloads sind jetzt größenbegrenzt (10 GiB,
analog zum bestehenden Zip-Bomben-Schutz); ein WebDAV-Upload mit nicht lesbarem Quell-Stream
scheitert jetzt sichtbar statt eine leere Datei als Erfolg zu melden; ein abgebrochener
Kopiervorgang meldet den noch unvollständigen Ordner nicht mehr als Erfolg; ZIP-Extraktion
begrenzt jetzt zusätzlich zur Gesamtgröße auch die Eintragsanzahl (Schutz vor
Viele-winzige-Einträge-Zip-Bomben).

**Suite-Integration (2026-09-03, s. `analyse.md` Abschnitt 5):** Die App war bisher nur
"Teilen"-Empfänger (`ACTION_SEND`/`SEND_MULTIPLE`), nie "Öffnen mit"-Ziel oder Datei-Picker für
andere Apps. Jetzt zusätzlich: `ACTION_VIEW` (z. B. ein E-Mail-Anhang, eine
Download-Benachrichtigung) kopiert die fremde Uri in einen frischen Cache-Ordner und öffnet ihn
wie einen ganz normalen, nur mit dieser einen Datei gefüllten Ordner (`data/share/IncomingView.kt`)
— derselbe, bereits getestete Tap-Dispatch (eigener Betrachter/"Öffnen mit") wie überall sonst in
der App, kein separater Betrachter-Pfad; ein "Speichern" ergibt sich dabei kostenlos über die
normale Verschieben/Kopieren-Funktion. `ACTION_GET_CONTENT` schaltet den bestehenden Datei-Browser
in einen Auswahlmodus (`data/share/PickRequest.kt`) — ein Tap auf eine Datei gibt ihre
`content://`-Uri an die anfragende App zurück, statt sie zu öffnen. Der Auswahlmodus wertet dabei
den angefragten Typ (`Intent.getType()`/`EXTRA_MIME_TYPES`, s. `data/share/MimeTypeFilter.kt`) aus
und blendet nicht passende Dateien aus, und beherrscht mit `EXTRA_ALLOW_MULTIPLE` die
Mehrfachauswahl (Rückgabe als `ClipData`).

**Produktionsreife (2026-09-04, s. `../analyse.md` Abschnitt 7):** Alle nutzersichtbaren Texte
liegen jetzt in `res/values/strings.xml` — vorher standen 56 davon als Literal direkt im
Compose-Code, während Galerie, Kamera und Warden bereits durchgängig `stringResource` nutzten.
Dabei fiel eine Kopplung auf, die eine spätere Übersetzung lautlos kaputtgemacht hätte:
`HomeScreen` wählte das Icon eines Speicherorts über dessen deutschen Anzeigetext
(`when (label) { "Bilder" -> ... }`). `StorageRoot` und `QuickAccessFolder` tragen deshalb jetzt
eine `Kind`-Aufzählung statt einer fertigen Beschriftung; aufgelöst wird sie in der UI. Neu im
Über-Bildschirm: die installierte Version **mit Versionscode** (bei Sideload die eindeutige
Angabe für einen Fehlerbericht) und ein Verweis auf die Releases-Seite — die App prüft bewusst
nicht selbst auf Updates, der Verweis wird vom Browser geöffnet.

- **Barrierefreiheit (analyse.md 7-10):** Der Auswahlzustand einer Zeile bzw. Kachel steckte
  ausschließlich im Häkchen-Symbol, das keine Beschreibung trug — für eine Vorlesehilfe war
  nicht erkennbar, was ausgewählt ist, und der leere Kreis für „nicht ausgewählt" hätte auch mit
  Beschreibung nichts anzusagen gehabt. Der Zustand hängt jetzt als Standard-Semantik `selected`
  an der Zeile/Kachel selbst. Das Kategoriesymbol (lokal wie im WebDAV-Browser) sagt jetzt die
  Dateiart an — es ist die einzige Stelle, an der „Ordner" von „Datei" zu unterscheiden ist, der
  Name leistet das nicht, weil beide endungslos sein können. Miniaturbilder bleiben bewusst ohne
  Beschreibung: der Dateiname steht als Text in derselben Kachel. Dabei fiel ein echter Fehler
  auf — der **Zurück-Knopf im Papierkorb war völlig unbeschriftet**.

**Noch nicht enthalten** (mögliche weitere Ausbauschritte): Volltextsuche in Dateiinhalten (die
Suche geht über Namen, nicht über Inhalte), weitere Archivformate (RAR/7z/TAR) — echte
Entpack-Implementierungen dafür, nicht nur das UI-Ausblenden von oben —, weitere
Netzwerkprotokolle (SMB/FTP), Digest-Auth für WebDAV, Upload-Empfang und HTTPS für die
WLAN/Hotspot-Freigabe, ein eigener Einstellungs-Bildschirm (die wenigen Einstellungen sitzen
derzeit dort, wo sie wirken), eine echte `DocumentsProvider`-Rolle (deutlich größere API-Fläche als
der einfache Intent-Vertrag oben — ein `DocumentsProvider` würde z. B. Systemordner-Navigation
direkt im fremden App-Picker erlauben).


- **App-Icon (analyse.md 7-13):** Files, Kamera und Galerie teilten sich bis 2026-09-04
  dieselbe Ordner-Grafik, im XML selbst als Platzhalter vermerkt. Files behält als Dateimanager
  den Ordner, aber als eigene, für die 108dp-Leinwand gezeichnete Fassung mit zwei ausgesparten
  Dokumentzeilen.

## Build

```
./gradlew build
```

Debug-APK:

```
./gradlew :app:assembleDebug
```

## Test

```
./gradlew test                       # Unit-Tests (JVM)
./gradlew connectedDebugAndroidTest  # Instrumentation-Tests (Gerät/Emulator nötig)
```

`app/src/androidTest/` deckt seit 2026-09-04 die Teile ab, die ein JVM-Unit-Test nicht
erreicht (s. `../analyse.md` 7-01): das Encoding der Navigations-Routen (Befund 2-18), den
`ACTION_GET_CONTENT`-Vertrag samt Mime-Typ-Filter, den Empfang von `ACTION_SEND`/`ACTION_VIEW`
inklusive der Namens-Sanierung beim Kopieren in den Cache, und die beiden Dialoge, an denen im
Fehlerfall Daten verloren gehen — Papierkorb-Bestätigung und Konfliktauflösung.

Faustregel für die Zuordnung: alles, was `Intent`, `Uri`, `SharedPreferences` oder Compose
anfasst, gehört in `androidTest` — unter einem reinen JVM-Unit-Test liefert das gestubbte
`android.jar` dort nur `RuntimeException("Stub!")`. Reine Datei-/Sortier-/Formatierungslogik
bleibt Unit-Test, weil die ohne Gerät und in Sekunden läuft.

## Bekannte Einschränkung

Auf Android 10 (API 29) — nach Einführung von Scoped Storage, aber vor
`MANAGE_EXTERNAL_STORAGE` — bleibt der Zugriff auf app-eigene Verzeichnisse
beschränkt (s. `permission/StoragePermission.kt`-Klassendoc). Bewusst in Kauf
genommener Rand-Fall für eine seit Jahren nicht mehr aktuell gehaltene
Android-Version.

## License

See [LICENSE](../LICENSE). Fremdbibliotheken und deren Lizenzen:
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) — in der App unter „Über die App →
Fremdbibliotheken“.
