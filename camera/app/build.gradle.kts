plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.ble1st.camera"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "de.ble1st.camera"
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

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
