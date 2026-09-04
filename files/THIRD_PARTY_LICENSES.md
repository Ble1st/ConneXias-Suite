# Fremdbibliotheken und Lizenzen — ConneXias Files

ConneXias Files (`de.ble1st.files`) steht selbst unter der Apache License,
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
androidx.documentfile:documentfile
com.squareup.okhttp3:okhttp
com.squareup.okio:okio (transitiv über okhttp)
com.google.zxing:core
```

okhttp trägt den eigenen WebDAV-Client (data/webdav/); zxing:core erzeugt den
QR-Code der Netzwerkfreigabe
(ui/localshare/) und hat selbst keine Abhängigkeiten.

## Nur zur Bauzeit / in Tests

```
junit:junit (EPL-2.0) — nur Testabhängigkeit, nicht im APK
androidx.test.ext:junit, androidx.test.espresso:espresso-core (Apache-2.0)
  — nur Testabhängigkeiten, nicht im APK
```

## Lizenztext

Der Volltext der Apache License 2.0 steht in [LICENSE](../LICENSE) und, für Endnutzer erreichbar,
in `app/src/main/res/raw/third_party_licenses.txt`.
