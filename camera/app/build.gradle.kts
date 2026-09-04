plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.ble1st.camera"
    compileSdk {
        version = release(37)
    }

    // Release-Signing: nur gesetzt, wenn die Release-Pipeline (.github/workflows/release-apps.yml)
    // den Keystore-Pfad per Env-Var bereitstellt (dort aus dem Secret RELEASE_KEYSTORE_BASE64
    // dekodiert — derselbe Keystore wie bei Warden, s. dortige build.gradle.kts). Ein lokaler
    // `./gradlew assembleRelease` ohne diese Env-Vars liefert weiterhin eine unsignierte
    // Release-APK; kein Secret-Material nötig für einen lokalen Testbau.
    val releaseStoreFilePath = System.getenv("CONNEXIAS_RELEASE_STORE_FILE")
    signingConfigs {
        if (releaseStoreFilePath != null) {
            create("release") {
                storeFile = file(releaseStoreFilePath)
                storePassword = System.getenv("CONNEXIAS_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("CONNEXIAS_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("CONNEXIAS_RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "de.ble1st.camera"
        // API 26 (Android 8.0): erste Version mit Adaptive Icons, dieselbe Untergrenze wie
        // ConneXias Files ("gleiche Anforderungen") — kein Geräteverwaltungs-Feature wie bei
        // Warden (dort minSdk 35), soll möglichst viele noch aktive Geräte erreichen.
        minSdk = 26
        targetSdk = 37
        // Von der Release-Pipeline per -PconnexiasVersionCode/-PconnexiasVersionName aus dem
        // Git-Tag gesetzt (s. .github/workflows/release-apps.yml); ohne diese Properties (lokaler
        // Build) bleiben die Default-Werte stehen.
        versionCode = (project.findProperty("connexiasVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("connexiasVersionName") as String?) ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            if (releaseStoreFilePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // R8 bleibt hier bewusst aus — anders als bei Warden (dort seit 2026-08-25 aktiv, mit
            // eigenen Keep-Regeln für JNA/UniFFI). Diese App bündelt mit CameraX (inkl. Extensions/Camera2-Interop), Coil, Media3 und
            // ZXing mehrere reflektions- bzw. serviceloader-lastige Bibliotheken,
            // und R8s Umbenennung bricht so etwas typischerweise erst zur Laufzeit, nicht beim
            // Bauen. Ohne Testgerät in dieser Umgebung wäre das ungeprüft ausgeliefert — dieselbe
            // Abwägung wie bei den nicht umgesetzten Kamera-Features. Einschaltbar, sobald ein
            // Gerätetest gegen eine minifizierte Release-APK laufen kann.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

// Mehrere Compose-Material3-APIs, die diese App nutzt (u. a. Scaffold-Overloads, das
// lambda-basierte LinearProgressIndicator), sind in material3 1.4.0 noch mit
// @ExperimentalMaterial3Api markiert, obwohl sie längst der empfohlene, stabile Verwendungsweg
// sind — Modul-weites Opt-in statt @OptIn an jeder einzelnen Composable-Datei (dieselbe
// Begründung wie in ConneXias Files).
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}
// Camera2-Interop (data/camera/CameraController.kt: manuelle ISO-/Verschlusszeit-Steuerung über
// Camera2CameraControl/Camera2CameraInfo) trägt die AndroidX-eigene androidx.annotation.RequiresOptIn-
// Markierung statt Kotlins kotlin.RequiresOptIn — das Opt-in dafür sitzt deshalb direkt als
// `@file:androidx.annotation.OptIn(...)` in CameraController.kt, kein Kotlin-Compiler-Flag hier
// (dieses würde vom UnsafeOptInUsageError-Lint-Check nicht erkannt, s. dortiger Kommentar).

// Ausgabedatei "ConneXias-Kamera-release-1.2.3.apk" statt des generischen "app-release.apk" —
// vier Apks aus vier Ordnern landen als Anhänge auf derselben GitHub-Release-Seite und müssen
// dort auch ohne Ordnerkontext unterscheidbar sein (dieselbe Begründung wie in Wardens
// build.gradle.kts).
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                output.versionName.map { versionName ->
                    "ConneXias-Kamera-${variant.name}-$versionName.apk"
                }
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Vorschau + Foto-/Videoaufnahme — s. data/camera/CameraController.kt.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.extensions)

    // Kurz-Ansicht der letzten Aufnahme — s. ui/review/CaptureReviewScreen.kt.
    implementation(libs.coil.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // EXIF-Rotation beim Filtern (util/PhotoFilters.kt) — s. Kommentar in libs.versions.toml.
    implementation(libs.androidx.exifinterface)
    // QR-/Barcode-Scanner (data/scan/, ui/scan/) — s. Kommentar in libs.versions.toml.
    implementation(libs.zxing.android.embedded)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
