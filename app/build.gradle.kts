plugins {
    alias(libs.plugins.android.application)
    // Compose für die gesamte UI (Haupt-Status-Screen, App-Verwaltung/Scanner aus Herald, Presence/
    // PIN-Screens aus Sentinel) — ein einziges Modul, kein Grund, Compose modulweise zu staffeln
    // wie im Quellprojekt (dort erst ab Meilenstein A.5 zugefügt, weil :core:* Module reine
    // Logik-Module ohne UI waren).
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.ble1st.warden"
    compileSdk {
        version = release(37)
    }

    // Release-Signing: nur gesetzt, wenn die Release-Pipeline (.github/workflows/release.yml) den
    // Keystore-Pfad per Env-Var bereitstellt (dort aus dem Secret RELEASE_KEYSTORE_BASE64
    // dekodiert). Ein lokaler `./gradlew assembleRelease` ohne diese Env-Vars liefert weiterhin
    // eine unsignierte Release-APK — kein Secret-Material nötig für einen lokalen Testbau.
    val releaseStoreFilePath = System.getenv("WARDEN_RELEASE_STORE_FILE")
    signingConfigs {
        if (releaseStoreFilePath != null) {
            create("release") {
                storeFile = file(releaseStoreFilePath)
                storePassword = System.getenv("WARDEN_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("WARDEN_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("WARDEN_RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "de.ble1st.warden"
        minSdk = 35
        targetSdk = 37
        // Von der Release-Pipeline per -PwardenVersionCode/-PwardenVersionName aus dem Git-Tag
        // gesetzt (siehe .github/workflows/release.yml); ohne diese Properties (lokaler Build)
        // bleiben die bisherigen Default-Werte.
        versionCode = (project.findProperty("wardenVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("wardenVersionName") as String?) ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            if (releaseStoreFilePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        // Architektur-Review 2026-08-24 (F-7): VERSION_11 war der historische Kompromiss für
        // niedrige minSdk-Werte ohne Core-Library-Desugaring — bei minSdk 35 (Android 15) längst
        // gegenstandslos, die Plattform bringt die moderne java.*-API-Fläche nativ mit.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // DestructiveCommandGuard/WardenLockTaskGate-Drill-Trigger brauchen BuildConfig.DEBUG —
        // AGP generiert BuildConfig seit AGP 8 nicht mehr automatisch.
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // Presence: BiometricPrompt-Pfad (PresenceManager).
    implementation(libs.androidx.biometric)
    // Verdachtsscanner (SuspiciousAppScanWorker), periodisches Polling.
    implementation(libs.androidx.work.runtime.ktx)
    // WardenLock: App-weites Foreground/Background für WardenLockSession.
    implementation(libs.androidx.lifecycle.process)
    // UniFFI-generierte Bindings (uniffi/connexias_engine/) brauchen JNA zur Laufzeit für die
    // FFI-Aufrufe in die native Engine (src/main/jniLibs/, per rust/build-android.sh erzeugt).
    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }
    // Timber-Fassade vor LocalRingTree (crypto/LocalRingTree.kt extends Timber.Tree).
    implementation(libs.timber)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.work.testing)
}
