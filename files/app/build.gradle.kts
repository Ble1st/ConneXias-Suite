plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.ble1st.files"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "de.ble1st.files"
        // API 26 (Android 8.0): erste Version mit Adaptive Icons — die App verzichtet dadurch
        // komplett auf die klassischen density-spezifischen Launcher-PNGs (nur noch
        // mipmap-anydpi-v26/), s. res/mipmap-anydpi-v26. Niedriger als warden (minSdk 35, dort
        // Device-Owner-Voraussetzung), weil ein Dateimanager kein Geräteverwaltungs-Feature
        // braucht und möglichst viele noch aktive Geräte erreichen soll.
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

// Mehrere Compose-Material3-APIs, die diese App nutzt (u. a. ListItem, das lambda-basierte
// LinearProgressIndicator, Scaffold-Overloads), sind in material3 1.4.0 noch mit
// @ExperimentalMaterial3Api markiert, obwohl sie längst der empfohlene, stabile Verwendungsweg
// sind — Modul-weites Opt-in statt @OptIn an jeder einzelnen Composable-Datei.
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
    // SAF-Import/-Export (Cloud-Storage-Provider, SD-Karte ohne All-Files-Access) über
    // DocumentFile — s. FileOperations.kt / StorageRoots.kt.
    implementation(libs.androidx.documentfile)
    // Eigener Bildbetrachter/Videoplayer (Nutzeranforderung) — s. ui/viewer/ImageViewerScreen.kt,
    // VideoPlayerScreen.kt.
    implementation(libs.coil.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // WebDAV-Netzwerkspeicher-Anbindung — s. data/webdav/.
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
