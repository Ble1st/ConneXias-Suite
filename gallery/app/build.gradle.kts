plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.ble1st.gallery"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "de.ble1st.gallery"
        // API 26 (Android 8.0): erste Version mit Adaptive Icons, dieselbe Untergrenze wie
        // ConneXias Files ("gleiche Anforderungen") — kein Geräteverwaltungs-Feature wie bei
        // Warden (dort minSdk 35), soll möglichst viele noch aktive Geräte erreichen.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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
