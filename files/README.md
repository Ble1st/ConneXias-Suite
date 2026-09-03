# ConneXias Files

FOSS-Dateimanager (`de.ble1st.files`), Android, ein einziges Gradle-Modul. Kein
Google-Play-Services-/proprietärer-Google-Dienst verbaut; Material 3 (mit einer
expressive-nahen Formsprache, s. `ui/theme/Theme.kt`), Jetpack Compose.

## Umfang (Stand: Kern-Ausbauschritt + eigene Betrachter)

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
  mit verschlüsselt gespeicherten Zugangsdaten (`EncryptedSharedPreferences`).
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
  persistiert (`ViewModePreference`). Miniaturbilder über Coil nur für Bilder; MVP-Fassung ohne
  Video-Thumbnails (bräuchten `coil-video`) und ohne eigenes Pro-Kachel-Menü — Öffnen/Auswählen wie
  in der Listenansicht, Aktionen laufen über die vorhandene Auswahl-Toolbar.
- Zuletzt verwendet (2026-09-03, `data/recent/RecentFilesStore.kt`): neuer Home-Bereich mit den
  zuletzt tatsächlich geöffneten Dateien (nicht Ordnern), protokolliert am zentralen Tap-Dispatch
  in `FilesNavHost.kt`. Tap öffnet über denselben Betrachter-Pfad wie im Browser; eine
  zwischenzeitlich gelöschte oder verschobene Datei fällt beim nächsten Laden still aus der Liste.
- WLAN/Hotspot-Freigabe (2026-09-03, `data/localshare/`, `ui/localshare/LocalShareScreen.kt`): der
  aktuell geöffnete Ordner lässt sich über einen selbstgeschriebenen, minimalen HTTP-Server
  (`LocalHttpServer`, kein `nanohttpd` — seit 2016 unmaintained, s. dortiges Klassendoc) für jeden im
  selben WLAN/Hotspot per Link freigeben; erreichbar über das neue Netzwerk-Symbol im Datei-Browser.
  Token-Pflicht in der URL (kein offener Server), Pfad-Traversal-Schutz gegen Anfragen von außen.
  MVP-Fassung: nur Herunterladen (kein Upload-Empfang), nur Text-Link zum Kopieren/Teilen statt eines
  QR-Codes, kein HTTPS (reines Heimnetz-Szenario, kein öffentliches Internet).

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
entsprechenden Aktionen sind gesperrt, solange bereits einer läuft; "Entpacken" wird nur noch für
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
`content://`-Uri an die anfragende App zurück, statt sie zu öffnen.

**Noch nicht enthalten** (mögliche weitere Ausbauschritte): rekursive/globale Suche,
weitere Archivformate (RAR/7z/TAR) — echte
Entpack-Implementierungen dafür, nicht nur das UI-Ausblenden von oben —, weitere
Netzwerkprotokolle (SMB/FTP), Digest-Auth für WebDAV, Video-Thumbnails in der Grid-Ansicht,
Upload-Empfang und HTTPS für die WLAN/Hotspot-Freigabe, Settings-Screen,
`GET_CONTENT` mit angefragtem Mime-Typ-Filter (jeder Dateityp wird aktuell zurückgegeben,
unabhängig davon, wonach der Aufrufer fragt) und Mehrfachauswahl (`EXTRA_ALLOW_MULTIPLE`), eine
echte `DocumentsProvider`-Rolle (deutlich größere API-Fläche als der einfache Intent-Vertrag oben —
ein `DocumentsProvider` würde z. B. Systemordner-Navigation direkt im fremden App-Picker
erlauben), echte Job-Warteschlange (mehrere Jobs sind jetzt UI-seitig gesperrt statt still
verworfen, aber es gibt weiterhin keine Queue, die sie nacheinander abarbeitet).

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
./gradlew test
```

## Bekannte Einschränkung

Auf Android 10 (API 29) — nach Einführung von Scoped Storage, aber vor
`MANAGE_EXTERNAL_STORAGE` — bleibt der Zugriff auf app-eigene Verzeichnisse
beschränkt (s. `permission/StoragePermission.kt`-Klassendoc). Bewusst in Kauf
genommener Rand-Fall für eine seit Jahren nicht mehr aktuell gehaltene
Android-Version.

## License

See [LICENSE](../LICENSE).
