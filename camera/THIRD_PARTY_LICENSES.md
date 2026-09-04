# Fremdbibliotheken und Lizenzen — ConneXias Kamera

ConneXias Kamera (`de.ble1st.camera`) steht selbst unter der Apache License,
Version 2.0 — s. [LICENSE](../LICENSE) im Repository-Wurzelverzeichnis, gültig für alle vier Apps
der ConneXias Suite gleichermaßen.

**Alle im APK enthaltenen Fremdbibliotheken stehen ebenfalls unter der Apache License, Version
2.0.** Es gibt in dieser App keine Abhängigkeit mit einer abweichenden Lizenz — deshalb reicht
hier eine Aufstellung plus ein einziger Lizenzverweis, statt für jede Bibliothek einen eigenen
Lizenztext abzudrucken.

Diese Datei ist die im Repository gepflegte Quelle. Die für Endnutzer erreichbare Kopie liegt als
`app/src/main/res/raw/third_party_licenses.txt` in der App selbst (Über → Fremdbibliotheken) —
Apache-2.0 §4(d) verlangt, dass die Attribution dem *Empfänger der Binary* zugänglich ist, eine
Datei im Repository allein genügt dafür nicht, sobald eine APK per Sideload weitergegeben wird.
Beide Dateien werden bewusst parallel gepflegt; wer hier etwas ändert, ändert es dort mit.

## Im APK enthalten (alle Apache-2.0)

```
androidx.core:core-ktx
androidx.compose:compose-bom (ui, ui-tooling-preview, material3,
  material-icons-extended)
androidx.activity:activity-compose
androidx.lifecycle:lifecycle-runtime-ktx,
  lifecycle-runtime-compose, lifecycle-viewmodel-compose
androidx.navigation:navigation-compose
io.coil-kt.coil3:coil-compose
androidx.media3:media3-exoplayer, media3-ui
org.jetbrains.kotlin:kotlin-stdlib
org.jetbrains.kotlinx:kotlinx-coroutines-android
androidx.camera:camera-core, camera-camera2, camera-lifecycle,
  camera-view, camera-video, camera-extensions
androidx.exifinterface:exifinterface
com.journeyapps:zxing-android-embedded
com.google.zxing:core (transitiv über zxing-android-embedded)
```

Die CameraX-Artefakte (androidx.camera.*) sind AndroidX/Google-OSS ohne
Play-Services-Laufzeitabhängigkeit; camera-extensions spricht die vom
Gerätehersteller im Camera2-HAL bereitgestellte Vendor-Implementierung an,
keinen Google-Cloud-Dienst. zxing-android-embedded zieht ausschließlich
com.google.zxing:core nach (per POM geprüft) — kein ML Kit, keine
Play Services.

## Nur zur Bauzeit / in Tests

```
junit:junit (EPL-2.0) — nur Testabhängigkeit, nicht im APK
androidx.test.ext:junit, androidx.test.espresso:espresso-core (Apache-2.0)
  — nur Testabhängigkeiten, nicht im APK
```

## Lizenztext

Der Volltext der Apache License 2.0 steht in [LICENSE](../LICENSE) und, für Endnutzer erreichbar,
in `app/src/main/res/raw/third_party_licenses.txt`.
