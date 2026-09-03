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
- "Öffnen mit ConneXias Galerie" (`ACTION_VIEW`) und Bild-/Video-Auswahl für fremde Apps
  (`ACTION_PICK`/`ACTION_GET_CONTENT`) — s. `MainActivity`/`nav/ExternalIntent.kt`. ConneXias
  Kameras "In Galerie öffnen" landet dadurch jetzt hier statt in der System-Default-Galerie.
- Album-skalierter Bild-Betrachter-Pager für benutzerdefinierte Alben (Wisch-Geschwister sind nur
  die Album-Elemente, nicht mehr die gesamte Galerie), Umbenennen-UI für benutzerdefinierte Alben,
  Kachel-Anzahl zählt nur noch tatsächlich noch vorhandene Elemente (nicht mehr aus MediaStore
  gelöschte, aber noch referenzierte IDs mit).

**Sicherheits-/Robustheitsfixes (2026-09-02, s. `analyse.md`):** ein `http://`-Server im
Cloud-Sync-Formular zeigt jetzt eine sichtbare Warnung; Zugangsdaten werden erst nach
erfolgreichem Verbindungstest persistiert statt sofort beim Tippen auf den Button, ein leeres
Passwort wird nicht mehr akzeptiert; der Remote-Dateiname beim Hochladen enthält die MediaStore-ID
als Präfix, damit zwei unabhängige Fotos mit demselben Dateinamen sich nicht mehr gegenseitig
überschreiben; ein laufender Sync übersteht jetzt Navigation weg vom Cloud-Sync-Screen (eigener
prozessweiter Coroutine-Scope statt eines an die Compose-Navigation gebundenen) — echte
Process-Tod-Sicherheit bräuchte weiterhin WorkManager, s. unten; `PhotoEditor` decodiert Fotos
jetzt herunterskaliert (`inSampleSize`) statt in voller Sensorauflösung (OOM-Risiko bei hochauf-
lösenden Fotos) und korrigiert die EXIF-Rotation vor dem Speichern, statt sie zu verwerfen (ein im
Hochformat aufgenommenes, aber mit gedrehtem Sensor gespeichertes Foto landete vorher seitwärts in
der bearbeiteten Kopie); `MediaStoreRepository`s `ContentObserver` führt die MediaStore-Query bei
einer Änderung jetzt auf einem Hintergrund-Thread aus statt blockierend auf dem Main-Thread
(ANR-Risiko bei großen Bibliotheken); "Aufnahmedatum" sortiert/zeigt jetzt `DATE_TAKEN` mit
`DATE_ADDED`-Fallback statt ausschließlich `DATE_ADDED` (Aufnahme- statt Import-/Sync-Zeitpunkt).

**2. Durchgang (2026-09-03, s. `analyse.md`):** ein nicht lesbares Element (gelöscht, Berechtigung
entzogen) wurde beim Cloud-Sync bisher als leere Datei hochgeladen und trotzdem als erfolgreich
gesichert markiert (Server antwortet auf ein leeres PUT oft mit 2xx) — gilt jetzt explizit als
Fehlschlag, bevor überhaupt hochgeladen wird; ein `displayName` mit "/" landete durch den
Remote-Pfad-Segment-Splitter versehentlich in einem zusätzlichen Unterordner, wird jetzt saniert;
"Jetzt sichern" persistierte die Formularfelder bisher immer, ungetestet — derselbe Fehler, der
beim Test-Knopf schon behoben war, ist jetzt auch hier behoben (persistiert wird nur nach
erfolgreichem Test); `ACTION_VIEW` akzeptierte jede Uri mit numerischem letzten Pfadsegment ohne
Authority-Prüfung (`content://fremde.app/item/42` hätte MediaStore-Element 42 geöffnet), jetzt auf
`MediaStore.AUTHORITY` beschränkt; der System-Löschdialog-Callback (Betrachter, Grid, Papierkorb)
behandelte ein Abbrechen (`RESULT_CANCELED`) bisher wie einen Löscherfolg, prüft jetzt
`resultCode == RESULT_OK`; `CustomAlbumScreen` rief `onNavigateUp()` bei einem zwischenzeitlich
gelöschten Album direkt im Composable-Körper auf statt in einem Seiteneffekt (Absturz-/
Endlosschleifen-Risiko), jetzt in `LaunchedEffect`; Teilen setzte die Uri(s) nur als `EXTRA_STREAM`,
nicht zusätzlich im `ClipData` (manche Ziel-Apps brauchen Letzteres für den Lesezugriff); die
Bild-/Video-Auswahl für fremde Apps (`ACTION_PICK`/`GET_CONTENT`) gewährte keinen dauerhaften
Zugriff (`FLAG_GRANT_PERSISTABLE_URI_PERMISSION` fehlte, `takePersistableUriPermission` beim
Aufrufer scheiterte dadurch); der Bildeditor transkodierte auch dann verlustbehaftet neu (und verlor
dabei alle EXIF-Metadaten), wenn weder Filter noch Zuschnitt das Bild überhaupt verändert hätten
(Filter NONE + Crop ORIGINAL kopiert jetzt die Originalbytes unverändert); derselbe
Pending-Leiche-Fehler wie bei ConneXias Kameras Filter-Speichern (s. dort) betraf auch
`PhotoEditSaver.saveEdited` — behoben; Compose Navigation dekodierte Album-/Bucket-Namen aus der
Route bereits selbst (`Uri.decode()` intern), ein zusätzliches `URLDecoder.decode()` in
`Routes.decodeName` dekodierte ein zweites Mal, zusammen mit einem latenten Encoding-Mismatch
(`URLEncoder`s "+" für Leerzeichen vs. `Uri.decode()`, das nur Prozent-Escapes versteht) — ein
Albumname mit Leerzeichen kam als "Name+mit+Leerzeichen" an. Jetzt kodiert `Routes.encode` mit
`Uri.encode` und die App decodiert selbst gar nicht mehr.

**Suite-Integration (2026-09-03, s. `analyse.md` Abschnitt 5):** Die App war bisher nur
"Öffnen mit"-Ziel für ein einzelnes MediaStore-Element sowie Bild-/Video-Auswahl für fremde Apps,
nie SEND-Empfänger. Jetzt zusätzlich `ACTION_SEND`/`ACTION_SEND_MULTIPLE` ("Teilen mit ConneXias
Galerie" aus Browser/Chat-Client/anderer Kamera-App) — `data/media/SharedMediaImporter.kt` legt
jede empfangene Uri als eigenständige MediaStore-Kopie unter `Pictures`/`Movies "ConneXias
Galerie"` an (reine Byte-Kopie, kein Decode/Recompress, erhält also Originalqualität und
EXIF-Metadaten), analog zu ConneXias Files' Teilen-Empfang.

**Auswahl leckte über Alben hinweg (2026-09-03).** `GalleryViewModel.selection` ist bewusst ein
einziges, über den ganzen NavHost geteiltes StateFlow — ohne einen Reset beim Bucket-Wechsel zeigte
ein frisch geöffnetes Album sofort den Auswahlmodus (Top-Bar mit Löschen/Teilen/Info) mit dem Stand
aus dem zuletzt verlassenen Album. `MediaGridScreen` leert die Auswahl jetzt bei jedem tatsächlichen
Bucket-Wechsel (nicht bei jeder Rekomposition — Zurückkommen von einem Betrachter zum selben Album
verwirft sie weiterhin nicht).

**Noch nicht enthalten** (mögliche weitere Ausbauschritte, mit Begründung):
- **Gesichtsgruppierung**: würde On-Device-Gesichtserkennung brauchen — die gängige Lösung dafür
  (ML Kit) ist ein Google-Dienst und verstößt gegen die "kein Google Play Services/kein
  proprietärer Google-Dienst"-Vorgabe dieser Suite; eine echte FOSS-Alternative (eigenes
  On-Device-Modell) wäre ein eigenständiges, deutlich größeres ML-Ausbauprojekt. Bewusst
  ausgelassen statt eines Kompromisses, der die Grundvoraussetzung der App verletzt.
- **Bidirektionaler Cloud-Sync**: die eingebaute Cloud-Sicherung ist bewusst nur eine
  Ein-Wege-Sicherung (Gerät → Server); echte Zwei-Wege-Synchronisation bräuchte
  Versions-/Konflikterkennung und ein Downloadstadium — eigenständiger Ausbauschritt.
- **WorkManager für Cloud-Sync**: ein laufender Sync übersteht jetzt Navigation weg vom Screen
  (s. oben), aber keinen echten Process-Tod (App vom System im Hintergrund gekillt) — dafür bräuchte
  es einen `WorkManager`-`Worker` mit eigener Fortschritts-Notification, ein für v1 bewusst
  zurückgestellter, in sich abgeschlossener Ausbauschritt.
- **Frei ziehbarer Zuschnitt**: der Bildeditor bietet feste Seitenverhältnis-Voreinstellungen statt
  frei ziehbarer Eckgriffe (eigene Ziehgesten-Erkennung wäre ein separater Ausbauschritt).
- **MediaStore-Paging**: die gesamte Bibliothek liegt weiterhin komplett im Speicher (`allItems`);
  für sehr große Bibliotheken (mehrere zehntausend Elemente) eigenständiger Ausbauschritt Richtung
  `Paging3`. Der akute ANR-Auslöser (Query auf dem Main-Thread bei jeder Änderung) ist behoben,
  s. oben — Paging selbst bleibt offen.

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
