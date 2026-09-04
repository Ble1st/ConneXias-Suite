import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject

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

        // "Andere Optimierungen": ohne abiFilters landen auch die vier JNA-Legacy-ABIs
        // (armeabi, mips, mips64 — Prä-Ice-Cream-Sandwich bzw. nie relevant gewordene
        // Android-Architekturen) unverändert im APK, obwohl libconnexias_engine.so (rust/
        // build-android.sh) ohnehin nur für diese vier Ziel-ABIs gebaut wird und minSdk 35
        // sowieso kein Gerät mit einer der drei Legacy-ABIs zulässt. Warden verteilt eine rohe
        // APK über GitHub Releases (kein Play-Store-App-Bundle mit Geräte-spezifischen Splits,
        // s. .github/workflows/release.yml) — jede tote ABI hier landet unverändert bei jedem
        // Nutzer im Download.
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        
        // UniFFI-Bindings für Barbican (Netz-Sperre)
        sourceSets {
            getByName("main") {
                java {
                    srcDir("src/main/uniffi")
                }
            }
        }
    }

    buildTypes {
        release {
            if (releaseStoreFilePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // F-4 (öffentliches-Repo-Checkliste): R8-Shrinking/-Obfuskierung für den Release-Bau.
            // `includeDefault` (Default `true`) bringt weiterhin die Standard-Android-Regeln
            // (proguard-android-optimize.txt) automatisch mit. Die eigenen Regeln kommen nicht
            // über `keepRules { files.from(...) }` (AGP 9.3: deprecated zugunsten des neuen
            // Source-Set-Konzepts) rein, sondern per Konvention aus src/release/keepRules/ (Regel-
            // dateien dort müssen auf `.keep` enden, sonst bricht minifyReleaseWithR8 hart ab) —
            // s. dortige warden.keep für die Begründung der einzelnen Regeln.
            optimization {
                enable = true
            }
            // Ressourcen-Shrinking (unbenutzte Drawables/Strings/Layouts) — unabhängig vom
            // Code-Shrinking oben, bleibt auf der alten `isShrinkResources`-DSL (in AGP 9 nicht
            // Teil von `optimization { }`, s. Recherche zur AGP-9-DSL-Aufteilung).
            isShrinkResources = true
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
        // Concord-Bus-Rückkanal für den ausgelagerten Barbican-Prozess (2026-08-31, Design-Dok
        // docs/design-barbican-prozess-childvpn.md) — IConcordBus.aidl unter src/main/aidl/. AGP
        // 8+ kompiliert .aidl-Dateien nur noch nach explizitem Opt-in, anders als früher implizit.
        aidl = true
    }
}

// APK-Dateiname statt des generischen, vom Modulnamen (":app") abgeleiteten "app-release.apk"/
// "app-debug.apk" — betrifft nur den Dateinamen, nicht das App-Label (@string/app_name ist
// bereits "Warden"). Versionsname mit rein, damit z. B. eine von der Release-Pipeline erzeugte
// "Warden-release-1.2.3.apk" auf der GitHub-Release-Seite auch ohne Ordnerkontext eindeutig ist.
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                output.versionName.map { versionName -> "Warden-${variant.name}-$versionName.apk" }
            )
        }
    }
}

// Sentinel-APK ins jeweilige Asset-Set kopieren, damit SentinelSilentInstaller sie ohne externen
// Dateitransfer installieren kann (Plan-Entscheidung "Verteilung: als Asset gebündelt"). Pro
// Variante (debug/release) die jeweils gleich signierte Sentinel-Variante — sonst würde die
// Signature-Permission-Kopplung (sentinel/src/main/AndroidManifest.xml) schon beim ersten lokalen
// Debug-Test fehlschlagen (debug-signiertes Warden neben einer anders/gar nicht signierten
// Sentinel-APK).
//
// Läuft über AGP's "generated assets"-Variant-API (`addGeneratedSourceDirectory`), NICHT über
// direktes Schreiben in `src/$variant/assets` (ursprünglicher, einfacherer Ansatz) — Letzteres
// bringt zwar `merge<Variant>Assets` zuverlässig zum Aufsammeln der Datei, verletzt aber Gradles
// Task-Output-Validierung für JEDEN anderen Task, der `src/main/assets` ebenfalls liest (z. B.
// `lintAnalyzeDebug`/`generateDebugLintReportModel` — "Property has implicit dependency", da
// diese Tasks den von `copySentinelApkFor<Variant>` geschriebenen Ordnerinhalt konsumieren, ohne
// dass Gradle die Abhängigkeit kennt). Die Variant-API löst das strukturell für ausnahmslos jeden
// Konsumenten (Merge, Lint, ...) statt Task für Task manuell nachzurüsten.
abstract class CopySentinelApkTask @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sentinelApkDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        fileSystemOperations.copy {
            from(sentinelApkDir) { include("*.apk") }
            into(outputDir)
            rename { "sentinel.apk" }
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val variantNameCapitalized = variant.name.replaceFirstChar { it.uppercase() }
        val copyTask = tasks.register<CopySentinelApkTask>("copySentinelApkFor$variantNameCapitalized") {
            dependsOn(":sentinel:assemble$variantNameCapitalized")
            sentinelApkDir.set(project(":sentinel").layout.buildDirectory.dir("outputs/apk/${variant.name}"))
            outputDir.set(layout.buildDirectory.dir("generated/sentinelAsset/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyTask, CopySentinelApkTask::outputDir)
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
    // ChildVPN-Config-Import per QR-Code scannen (NetworkScreen.kt, ChildVpnSection).
    implementation(libs.zxing.android.embedded)
    // Status-Widget (widget/WardenStatusWidget.kt) — Feature 6 aus docs/umsetzungsplan-7-features.md,
    // ursprünglich 2026-08-29 bewusst nicht gebaut (s. dortige Abwägung), jetzt als reine
    // Status-Anzeige nachgereicht (kein Tap-Aktions-Bypass um WardenLockActivity herum).
    implementation(libs.androidx.glance.appwidget)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.work.testing)
}
