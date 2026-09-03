# ConneXias Kamera

FOSS-Kamera-App (`de.ble1st.camera`), Android, ein einziges Gradle-Modul. Kein
Google-Play-Services-/proprietärer-Google-Dienst verbaut (CameraX ist reines AndroidX/Google-OSS
ohne Play-Services-Laufzeitabhängigkeit); Material 3 (mit einer expressive-nahen Formsprache, s.
`ui/theme/Theme.kt`), Jetpack Compose.

## Umfang (Stand: v1)

- Vollflächiger Kamera-Sucher (CameraX `PreviewView`) mit Foto- und Videomodus.
- Foto: Blitz Auto/An/Aus, Pinch-Zoom + Zoom-Regler, Tap-to-Focus, Raster-Overlay (Drittel-Raster),
  Selbstauslöser (Aus/3 s/10 s).
- Video: Dauerlicht (Torch) an/aus, dieselben Zoom-/Fokus-/Raster-/Selbstauslöser-Funktionen wie
  Foto, Aufnahmedauer-Anzeige während der laufenden Aufnahme.
- Kamera wechseln (vorne/hinten).
- Speicherung ausschließlich über `MediaStore` (`data/storage/MediaStoreSaver.kt`) in
  `DCIM/ConneXias Kamera` — Scoped-Storage-konform, keine `MANAGE_EXTERNAL_STORAGE`-
  Sonderberechtigung nötig (anders als ConneXias Files, das den kompletten Speicher braucht).
- Kurz-Ansicht der letzten Aufnahme (`ui/review/CaptureReviewScreen.kt`): eigener Bild-/
  Videobetrachter (Coil / Media3 ExoPlayer, dieselben Bibliotheken wie ConneXias Files) mit
  Teilen, Löschen, "In Galerie öffnen".
- Bildschirmdrehung während der Aufnahme: Compose-UI bleibt bewusst im festen
  Hochformat-Layout, ein `OrientationEventListener` führt aber Foto-/Video-Ausgabedrehung
  (`ImageCapture`/`VideoCapture`-`targetRotation`) live nach — Aufnahmen landen unabhängig von der
  gehaltenen Geräteausrichtung korrekt gedreht in der Galerie.
- Belichtungskorrektur (EV-Regler, `CameraControl.setExposureCompensationIndex`) sowie — auf
  Geräten mit `MANUAL_SENSOR`-Fähigkeit (per Camera2-Characteristics zur Laufzeit geprüft, sonst
  ausgeblendet) — echte manuelle ISO-/Verschlusszeit-Regler über Camera2-Interop
  (`Camera2CameraControl`/`CaptureRequestOptions`).
- HDR über die vom Gerätehersteller im Camera2-HAL bereitgestellte "Camera Extension"
  (`androidx.camera.extensions.ExtensionsManager`) — AndroidX/FOSS, kein Cloud-/Play-Services-
  Dienst; nur im Foto-Modus, nur sichtbar, wenn das Gerät die Extension tatsächlich anbietet.
- Foto-Nachbearbeitung: sechs `ColorMatrix`-Filter (Original/Schwarz-Weiß/Sepia/Vintage/Kühl/Warm,
  `util/PhotoFilters.kt`) mit Live-Vorschau in der Kurz-Ansicht — speichert non-destruktiv eine
  neue MediaStore-Kopie, das Original bleibt unangetastet erhalten (Speichern selbst downsampled
  auf max. 4096 px Kantenlänge und berücksichtigt die EXIF-Rotation der Quelle, s.
  "Sicherheits-/Robustheitsfixes").
- **Suite-Kopplung**: "In Galerie öffnen" zielt zuerst explizit auf ConneXias Galerie
  (`de.ble1st.gallery`), fällt auf die generische `ACTION_VIEW`-Auswahl zurück, falls diese nicht
  installiert ist (`util/CaptureActions.kt`). Diese App selbst ist über zwei Wege als
  Aufnahmeziel für andere Apps ansprechbar: den System-Kamera-Contract
  (`android.media.action.IMAGE_CAPTURE`/`VIDEO_CAPTURE`, s. unten) sowie regulär über die
  gespeicherten `DCIM/ConneXias Kamera`-MediaStore-Einträge, die ConneXias Galerie ohnehin
  anzeigt.
- **System-Kamera-Contract** (`ACTION_IMAGE_CAPTURE`/`ACTION_VIDEO_CAPTURE`, `nav/
  CaptureRequestInfo.kt`): andere Apps (Messenger, Browser, ConneXias Files' "Foto aufnehmen")
  können diese App per `startActivityForResult` als Aufnahmeziel nutzen statt auf die
  Geräte-Default-Kamera beschränkt zu sein. Der angeforderte Modus (Foto/Video) sperrt den
  Foto-/Video-Umschalter im Sucher; die Kurz-Ansicht bekommt einen zusätzlichen
  "Verwenden"-Knopf, der die Aufnahme entweder in die vom Aufrufer übergebene `EXTRA_OUTPUT`-Uri
  kopiert oder — falls keine gesetzt ist — die eigene MediaStore-Uri als Ergebnis liefert; ein
  Zurück-Antippen im Sucher vor der ersten Aufnahme beendet die App mit `RESULT_CANCELED`.

**Noch nicht enthalten** (mögliche weitere Ausbauschritte, mit Begründung):
- **RAW-Aufnahme (DNG)**: CameraX bietet dafür keine Standard-`ImageCapture`-Ausgabeoption; eine
  korrekte Umsetzung bräuchte eine eigenständige Camera2-`RAW_SENSOR`-`ImageReader`-Pipeline neben
  CameraX' Use-Case-Bindung statt einer einfachen Erweiterung der bestehenden — eigenständiger,
  substanzieller Ausbauschritt.
- **Panorama**: echtes Zusammensetzen mehrerer Aufnahmen braucht Bildmerkmalserkennung/
  -abgleich (Feature-Matching/Homographie-Schätzung), keine CameraX-Bordfunktion — ein einfacher
  Seite-an-Seite-Zusammenschnitt ohne echtes Stitching wäre in den meisten Fällen unbrauchbar,
  wurde deshalb bewusst nicht als Notlösung eingebaut.
- **Zeitlupe**: setzt eine Camera2-`High-Speed-Capture-Session` voraus (eigene Session-Klasse,
  stark geräteabhängige Bildraten-/Auflösungskombinationen) — ohne Testgerät mit Hochgeschwindig-
  keitsaufnahme in dieser Umgebung nicht verifizierbar, deshalb nicht umgesetzt statt ungeprüft
  ausgeliefert.
- **Mehrkamera-Ansicht (gleichzeitig Front+Rück)**: CameraX' `ConcurrentCamera`-API wird nur von
  einer schmalen Geräteauswahl unterstützt und ebenfalls nicht in dieser Umgebung testbar.
- **Einstellungen werden nicht dauerhaft gespeichert** (Blitz-/Torch-/Raster-/Timer-Wahl, letzter
  Modus/Objektiv): setzen sich bei jedem App-Neustart auf die Defaults zurück — es gibt v1 bewusst
  keinen eigenen Settings-Bildschirm/keine Persistenzschicht dafür (`DataStore` o. Ä. wäre die
  naheliegende spätere Ergänzung).
- **`ACTION_IMAGE_CAPTURE_SECURE`** (Sperrbildschirm-Kamera-Contract) wird nicht unterstützt — das
  bräuchte zusätzliche Sperrbildschirm-Fensterflags/-Verhalten (z. B. `FLAG_SHOW_WHEN_LOCKED`) und
  eine eigene Abwägung, ob eine App aus dieser Suite überhaupt vor dem Entsperren erreichbar sein
  soll; bewusst nicht Teil dieses Ausbauschritts.

## Sicherheits-/Robustheitsfixes (2026-09)

- **Kamera blieb während der Review-Ansicht gebunden.** `releaseCamera()` lief nur bei `ON_PAUSE`
  (Activity-Lifecycle) — die Navigation Sucher→Review verlässt dieselbe `RESUMED`-Activity aber
  ohne `ON_PAUSE`. Ein zusätzlicher `DisposableEffect` im Sucher-Composable gibt die
  Kamera-Hardware jetzt auch beim reinen Compose-Navigationswechsel frei (LED/Session/Akku).
- **Doppelauslösung durch überlappende Aufnahmen.** `isBusy` deckte vorher nur Countdown/laufende
  Aufnahme ab, nicht das Zeitfenster zwischen Auslöser-Tap und fertig geschriebenem Foto — ein
  zweiter Tap während eines noch laufenden `takePhoto()` konnte zwei sich überlappende Aufnahmen
  auslösen. Neues `isCapturingPhoto`-Flag schließt diese Lücke.
- **Selbstauslöser nicht abbrechbar.** Ein versehentlich gestarteter Countdown musste bisher
  abgewartet werden. Tippen auf die Countdown-Anzeige bricht ihn jetzt ab (`cancelCountdown()`).
- **Bildschirm konnte während der Videoaufnahme durch Auto-Lock ausgehen.** `PreviewView.
  keepScreenOn` wird jetzt für die Dauer einer laufenden Aufnahme gesetzt.
- **`FLAG_SECURE` fehlte.** Sucher und Kurz-Ansicht ließen sich per Screenshot/Bildschirmaufnahme
  festhalten — beides setzt jetzt `FLAG_SECURE` für die Dauer ihrer Anzeige (`util/
  SecureScreenEffect.kt`).
- **Warden-Kamerasperre ohne verständliche Fehlermeldung.** Ein von Warden per
  `DevicePolicyManager.setCameraDisabled` gesperrtes Gerät zeigte bisher die rohe, kryptische
  CameraX-Bind-Fehlermeldung. `getCameraDisabled()` wird jetzt direkt abgefragt und zeigt
  stattdessen "Kamera durch Warden gesperrt".
- **`WRITE_EXTERNAL_STORAGE` (API 26–28) im Manifest deklariert, aber nie zur Laufzeit
  angefragt** — auf diesen API-Levels (vor Scoped Storage) scheiterte `MediaStore.insert`
  dadurch typischerweise mit einer `SecurityException`. Wird jetzt Teil der regulären
  Berechtigungsanfrage (nur auf API 26–28 hinzugefügt, ab API 29 wirkungslos).
- **Zoom-Regler fest auf 1×–8× verdrahtet**, unabhängig von der tatsächlichen Hardware — Geräte
  mit kleinerem/größerem Zoombereich konnten den Regler entweder nie ausreizen oder boten einen
  ungültigen Bereich an. Wird jetzt bei jedem Bind aus `Camera.cameraInfo.zoomState` gelesen.
- **Dauerlicht/Belichtungskorrektur nach einem Rebind verloren** (Kamerawechsel, Moduswechsel,
  Rückkehr aus dem Hintergrund): Torch wurde nicht erneut auf den Controller angewendet, die
  EV-Korrektur wurde stillschweigend auf 0 zurückgesetzt statt beibehalten. Beide Werte werden
  jetzt nach jedem Bind wiederhergestellt (EV nur, soweit im — ggf. neuen — Gerätebereich noch
  gültig).
- **Filter-Speichern ohne Downsampling/EXIF-Rotation, OOM-Risiko.** `PhotoFilterSaver.
  saveFiltered()` dekodierte die Quellaufnahme bisher in voller Auflösung und ignorierte eine
  eventuell vorhandene EXIF-Rotation. Nutzt jetzt dieselbe zweistufige
  `inSampleSize`-Downsampling- + `ExifInterface`-Rotationskorrektur wie ConneXias Galerie (max.
  4096 px Kantenlänge).

**2. Durchgang (2026-09-03, s. `analyse.md`):**
- **Auslöser konnte bei einem Rebind-Timing-Fenster dauerhaft tot bleiben.** `takePhoto`/
  `startVideoRecording` kehrten bei einem noch ungebundenen `ImageCapture`/`VideoCapture` (Bind
  läuft während Moduswechsel/`ON_RESUME` noch) still zurück, ohne Callback — `isCapturingPhoto`
  blieb dadurch bis zum nächsten `releaseCamera()` auf `true` hängen. Beide melden einen solchen
  Fehlschlag jetzt über einen Fehler-Callback, den die ViewModel zum Zurücksetzen nutzt.
- **Bind-Race ohne Generation/Cancel.** `bind()` hängte zwei verschachtelte Future-Callbacks an,
  ohne einen vorherigen, noch laufenden Bind abzubrechen — ein schneller Modus-/Objektivwechsel
  konnte dazu führen, dass der zuletzt ANKOMMENDE statt der zuletzt ANGEFORDERTE Bind gewinnt.
  Ein Generation-Zähler lässt jeden Callback eines inzwischen überholten `bind()`-Aufrufs
  kommentarlos abbrechen.
- **`EXTRA_OUTPUT`-Ziel bekam ein dauerhaftes Duplikat spendiert.** Eine per System-Kamera-Contract
  angefragte Aufnahme landete zuerst wie gewohnt in `DCIM/ConneXias Kamera`, wurde beim "Verwenden"
  zusätzlich zur `EXTRA_OUTPUT`-Ziel-Uri des Aufrufers kopiert — die interne Kopie blieb aber
  zusätzlich für immer als unerwünschtes zweites Element in der Galerie stehen. Wird nach
  erfolgreicher Übergabe jetzt gelöscht.
- **Teilen ohne `ClipData`.** `CaptureActions.share` setzte die zu teilende Uri bisher nur als
  `EXTRA_STREAM` — manche Ziel-Apps erwarten sie zusätzlich im `ClipData`, um den Lesezugriff zu
  gewähren.
- **Filter-Speichern konnte einen kaputten MediaStore-Eintrag hinterlassen.** Eine Exception beim
  Schreiben ließ `IS_PENDING=1` für immer stehen (unsichtbarer Eintrag); ein `null`-`OutputStream`
  setzte `IS_PENDING` trotzdem bedingungslos auf 0 (leerer, aber sichtbarer Eintrag). Beide Fälle
  löschen den halbfertigen Eintrag jetzt wieder, statt ihn in einem kaputten Zustand zu belassen.
- **Doppeltes URL-Decoding der Review-Route.** Compose Navigation dekodiert Pfad-Argumente beim
  Uri-Template-Matching bereits selbst — ein zusätzlicher `URLDecoder.decode()`-Aufruf in
  `Routes.decodeUriArg` dekodierte ein zweites Mal (falsch rekonstruierte Uri bei codierten
  Schrägstrichen, möglicher Absturz bei einem Rest-`%`). Entfernt.
- **Bildschirm ging während Countdown/Foto-Schreiben durch Auto-Lock aus.** `keepScreenOn` war nur
  an `isRecording` geknüpft, nicht an die übrigen "beschäftigt"-Phasen. Jetzt an `state.isBusy`
  geknüpft.

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

Auf Android 8–9 (API 26–28, vor Scoped Storage) legt `MediaStore.insert` Aufnahmen ohne
`RELATIVE_PATH`-Spalte (die gibt es dort noch nicht) im MediaStore-Standardverzeichnis der
jeweiligen Sammlung ab statt im eigenen `DCIM/ConneXias Kamera`-Unterordner — ein für v1 bewusst
in Kauf genommener Rand-Fall für seit Jahren nicht mehr aktuell gehaltene Android-Versionen
(dieselbe Abwägung wie ConneXias Files' API-29-Einschränkung).

Kamera-Vorschau und tatsächliche Aufnahmen lassen sich nur auf echter Hardware oder einem
Emulator mit virtueller Kamera verifizieren — in der Entwicklungsumgebung dieses Ausbauschritts
stand kein Gerät zur Verfügung, Verifikation beschränkte sich auf Unit-Tests und `assembleDebug`.

## License

See [LICENSE](../LICENSE).
