plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.ble1st.gallery"
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
        applicationId = "de.ble1st.gallery"
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
            // eigenen Keep-Regeln für JNA/UniFFI). Diese App bündelt mit Coil, Media3, OkHttp und EncryptedSharedPreferences mehrere
            // reflektionslastige Bibliotheken,
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

// Mehrere Compose-Material3-APIs, die diese App nutzt (u. a. ListItem, Scaffold-Overloads,
// TopAppBar mit Actions), sind in material3 1.4.0 noch mit @ExperimentalMaterial3Api markiert,
// obwohl sie längst der empfohlene, stabile Verwendungsweg sind — Modul-weites Opt-in statt
// @OptIn an jeder einzelnen Composable-Datei (dieselbe Begründung wie in ConneXias Files).
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

// Ausgabedatei "ConneXias-Galerie-release-1.2.3.apk" statt des generischen "app-release.apk" —
// vier Apks aus vier Ordnern landen als Anhänge auf derselben GitHub-Release-Seite und müssen
// dort auch ohne Ordnerkontext unterscheidbar sein (dieselbe Begründung wie in Wardens
// build.gradle.kts).
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                output.versionName.map { versionName ->
                    "ConneXias-Galerie-${variant.name}-$versionName.apk"
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

    // Thumbnail-Grid + Bildbetrachter — s. ui/grid/MediaGridScreen.kt, ui/viewer/ImageViewerScreen.kt.
    implementation(libs.coil.compose)
    // Videoplayer — s. ui/viewer/VideoPlayerScreen.kt.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // Cloud-Sync — s. data/webdav/.
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)
    // PhotoEditor — s. gradle/libs.versions.toml-Kommentar.
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    // Bucket-/Sortier-Unit-Tests brauchen ein android.net.Uri-Objekt (MediaItem.uri) rein als
    // Platzhalter-Referenz — s. gradle/libs.versions.toml-Kommentar zu dieser Abhängigkeit.
    testImplementation(libs.mockito)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
