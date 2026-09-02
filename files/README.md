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

**Noch nicht enthalten** (mögliche weitere Ausbauschritte): Papierkorb/Undo für
Löschvorgänge, rekursive/globale Suche, weitere Archivformate (RAR/7z/TAR) — echte
Entpack-Implementierungen dafür, nicht nur das UI-Ausblenden von oben —, weitere
Netzwerkprotokolle (SMB/FTP), Digest-Auth für WebDAV, Grid-/Thumbnail-Ansicht, Settings-Screen,
`GET_CONTENT`/`ACTION_VIEW`-Dokumentenanbieter-Rolle (die App kann Dateien empfangen, aber noch
nicht als Datei-Picker für andere Apps auftreten — eigenständiger Ausbauschritt, ein
`DocumentsProvider` ist eine deutlich größere API-Fläche als der Share-Empfang oben), echte
Job-Warteschlange (mehrere Jobs sind jetzt UI-seitig gesperrt statt still verworfen, aber es gibt
weiterhin keine Queue, die sie nacheinander abarbeitet).

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
