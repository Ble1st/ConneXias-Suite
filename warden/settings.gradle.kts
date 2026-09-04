pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Warden"
include(":app")
// Minimale Kiosk-PIN-App für den Lockdown-Modus (eigene APK, eigener Prozess/eigene UID, aber
// zwingend dasselbe Signing-Zertifikat wie :app — s. sentinel/build.gradle.kts-Kommentar). Kein
// :core:*-Mehrmodul-Aufbau wie im ConneXias-Framework-Quellprojekt: genau zwei Module reichen,
// s. Plan "Sentinel: eigenständige Kiosk-PIN-App für den Lockdown-Modus".
include(":sentinel")
 