# ConneXias Galerie

FOSS-Galerie-App (`de.ble1st.gallery`), Android, ein einziges Gradle-Modul. Kein
Google-Play-Services-/proprietärer-Google-Dienst verbaut; Material 3 (mit einer expressive-nahen
Formsprache, s. `ui/theme/Theme.kt`), Jetpack Compose.

## Umfang (Stand: v1)

- Liest Bilder + Videos über `MediaStore` (`data/media/MediaStoreRepository.kt`), live
  aktualisiert per `ContentObserver` — neue Aufnahmen von ConneXias Kamera (oder jeder anderen
  App) erscheinen ohne Neustart.
- Alben-Übersicht (`ui/albums/AlbumsScreen.kt`): virtuelles "Alle"-Album + jedes MediaStore-Bucket
  als eigene Kachel mit Titelbild und Elementanzahl.
- Thumbnail-Grid (`ui/grid/MediaGridScreen.kt`): Sortierung (Datum/Name/Größe), Mehrfachauswahl
  per Long-Press mit Teilen/Löschen/Informationen-Aktionen.
- Eigener Bildbetrachter (`ui/viewer/ImageViewerScreen.kt`): Wischen zwischen allen Bildern
  desselben Albums, Pinch-/Doppeltipp-Zoom — dasselbe Muster wie ConneXias Files' Bildbetrachter,
  hier auf MediaStore-`content://`-Uris statt `java.io.File`.
- Eigener Videoplayer (`ui/viewer/VideoPlayerScreen.kt`): Media3 ExoPlayer, komplett lokale
  Wiedergabe ohne Play-Services-Abhängigkeit — dasselbe Muster wie ConneXias Files.
- Informationen-Dialog (Abmessungen, Größe, Aufnahmedatum, Pfad).
- Löschen (`MediaActions.requestRemove`): ab API 30 verschiebt "Löschen" zunächst in den
  Papierkorb (`MediaStore.createTrashRequest`, System-Bestätigungsdialog) statt sofort endgültig
  zu löschen; darunter (kein Papierkorb-Konzept vor API 30) bleibt es beim direkten Löschen über
  `MediaStore.createDeleteRequest`/`RecoverableSecurityException`-Fallback wie zuvor.
- Papierkorb (`ui/trash/TrashScreen.kt`, nur ab API 30 erreichbar): Wiederherstellen oder
  endgültiges Löschen bereits in den Papierkorb verschobener Elemente.
- Volltextsuche (`ui/grid/MediaGridScreen.kt`): filtert die aktuell geladene Album-/"Alle"-Ansicht
  nach Dateiname — clientseitig, kein eigener MediaStore-Index.
- Benutzerdefinierte Alben (`data/album/`): rein virtuell (nur `MediaItem`-ID-Referenzen, keine
  verschobenen/kopierten Dateien) — "Zu Album hinzufügen" aus der Mehrfachauswahl im Grid,
  eigener Album-Screen mit "Aus Album entfernen" statt einer Löschen-Aktion.
- Diashow (`ui/viewer/SlideshowScreen.kt`): automatisch weiterlaufender Bild-Pager über ein
  Album, Tippen pausiert/setzt fort.
- Bildbearbeitung (`ui/editor/PhotoEditorScreen.kt`, `util/PhotoEditor.kt`): sechs
  `ColorMatrix`-Filter (wie ConneXias Kamera) + zentrierter Zuschnitt auf feste
  Seitenverhältnisse (Original/1:1/4:3/16:9) — speichert immer als neue, non-destruktive
  MediaStore-Kopie.
- Cloud-Sicherung (`data/webdav/`, `data/sync/`, `ui/sync/CloudSyncScreen.kt`): Ein-Wege-Backup
  auf einen selbst gehosteten WebDAV-Server (eigener schlanker OkHttp-Client, kein
  Cloud-SDK/proprietärer Dienst) — merkt sich lokal, welche Elemente schon hochgeladen wurden.

**Noch nicht enthalten** (mögliche weitere Ausbauschritte, mit Begründung):
- **Gesichtsgruppierung**: würde On-Device-Gesichtserkennung brauchen — die gängige Lösung dafür
  (ML Kit) ist ein Google-Dienst und verstößt gegen die "kein Google Play Services/kein
  proprietärer Google-Dienst"-Vorgabe dieser Suite; eine echte FOSS-Alternative (eigenes
  On-Device-Modell) wäre ein eigenständiges, deutlich größeres ML-Ausbauprojekt. Bewusst
  ausgelassen statt eines Kompromisses, der die Grundvoraussetzung der App verletzt.
- **Bidirektionaler Cloud-Sync**: die eingebaute Cloud-Sicherung ist bewusst nur eine
  Ein-Wege-Sicherung (Gerät → Server); echte Zwei-Wege-Synchronisation bräuchte
  Versions-/Konflikterkennung und ein Downloadstadium — eigenständiger Ausbauschritt.
- **Album-spezifischer Betrachter-Pager**: Tippen in einem benutzerdefinierten Album öffnet
  aktuell den regulären "Alle"-Bildbetrachter statt eines auf das Album beschränkten Pagers
  (bewusste v1-Vereinfachung, s. `CustomAlbumScreen.kt`-Klassendoc).
- **Frei ziehbarer Zuschnitt**: der Bildeditor bietet feste Seitenverhältnis-Voreinstellungen statt
  frei ziehbarer Eckgriffe (eigene Ziehgesten-Erkennung wäre ein separater Ausbauschritt).

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

Auf genau API 29 (Android 10) gewährt die `RecoverableSecurityException`-Bestätigung beim Löschen
fremder Einträge nur die Schreibberechtigung, führt das Löschen selbst aber nicht aus (anders als
`MediaStore.createDeleteRequest` ab API 30, s. `MediaActions.requestDelete`-Klassendoc) — ein
zweiter Tap auf "Löschen" nach der Bestätigung schließt den Vorgang ab. Für eine seit Jahren nicht
mehr aktuell gehaltene Android-Version bewusst nicht mit einer vollen Retry-Logik nachgebaut
(dieselbe Abwägung wie ConneXias Files' API-29-Einschränkung).

Der Cloud-Sync-Fortschritt ("bereits hochgeladen") wird rein lokal gemerkt
(`data/sync/CloudSyncState.kt`), nicht serverseitig abgeglichen — nach einem App-Datenreset oder
auf einem Zweitgerät gilt dieser Stand als leer, ein erneuter Sync-Lauf lädt betroffene Elemente
dann erneut hoch (überschreibt aber nichts, landet nur als zusätzliche Datei auf dem Server).

Die neu hinzugekommenen Funktionen (Papierkorb, Cloud-Sync, Bildbearbeitung, benutzerdefinierte
Alben, Diashow, Suche) sind größtenteils MediaStore-/Netzwerk-/Bitmap-gebunden und damit in dieser
Entwicklungsumgebung (kein Gerät/Emulator verfügbar) nicht über Unit-Tests hinaus verifizierbar —
Verifikation beschränkte sich auch hier auf `assembleDebug`, `lint` und die bereits vorhandenen
reinen Logik-Unit-Tests.

## License

See [LICENSE](../LICENSE).
