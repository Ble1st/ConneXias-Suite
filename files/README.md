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

**Noch nicht enthalten** (mögliche weitere Ausbauschritte): Papierkorb/Undo für
Löschvorgänge, rekursive/globale Suche, weitere Archivformate (RAR/7z/TAR),
weitere Netzwerkprotokolle (SMB/FTP), Digest-Auth für WebDAV, Grid-/
Thumbnail-Ansicht, Settings-Screen.

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
