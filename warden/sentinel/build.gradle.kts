plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.ble1st.warden.sentinel"
    compileSdk {
        version = release(37)
    }

    // Signing MUSS dasselbe Zertifikat wie :app verwenden — die gesamte Warden<->Sentinel-
    // Kommunikation ist über `signature`-Level-Permissions abgesichert (s. AndroidManifest.xml,
    // Plan-Abschnitt "Warum kein AIDL-Bus"). Dieselben Env-Vars wie app/build.gradle.kts, kein
    // eigenes Secret in der Release-Pipeline nötig/gewollt.
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
        applicationId = "de.ble1st.warden.sentinel"
        minSdk = 35
        targetSdk = 37
        // Dieselben Properties wie :app (release.yml setzt beide aus demselben Git-Tag) — Warden
        // bündelt Sentinel immer im Lockstep mit der eigenen Version, s.
        // SentinelSilentInstaller-Klassendoc (kein eigenständiger Downgrade-Schutz nötig).
        versionCode = (project.findProperty("wardenVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("wardenVersionName") as String?) ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Gleiche vier ABIs wie :app — dieselbe libconnexias_engine.so (rust/build-android.sh),
        // eigene Kopie unter sentinel/src/main/jniLibs/ (Plan-Entscheidung "Crypto-Sharing:
        // Duplizieren").
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            if (releaseStoreFilePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Gleiche R8-Begründung wie app/build.gradle.kts — eigene Keep-Regeln in
            // src/release/keepRules/sentinel.keep (gleicher Grund: JNA-Reflection-Bindung der
            // UniFFI-Bindings).
            optimization {
                enable = true
            }
            isShrinkResources = true
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

// Eigenständiger Dateiname (analog Wardens "Warden-<variant>-<version>.apk"), zusätzlich als
// eigenständiges Release-Asset hochgeladen (release.yml) UND von :app als assets/sentinel.apk
// gebündelt (app/build.gradle.kts, Plan-Abschnitt "Build/Release").
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                output.versionName.map { versionName -> "Sentinel-${variant.name}-$versionName.apk" }
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    // Eigene Kopie der UniFFI-generierten Bindings braucht JNA zur Laufzeit — dieselbe
    // Begründung wie app/build.gradle.kts.
    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.rules)
}
