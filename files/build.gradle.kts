// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Kein separates org.jetbrains.kotlin.android-Plugin: AGP 9+ bringt Kotlin-Support fest
    // eingebaut mit und untersagt das Plugin explizit
    // (https://kotl.in/gradle/agp-built-in-kotlin).
    alias(libs.plugins.kotlin.compose) apply false
}
