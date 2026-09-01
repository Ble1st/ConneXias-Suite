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
  neue MediaStore-Kopie, das Original bleibt unangetastet erhalten.

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
