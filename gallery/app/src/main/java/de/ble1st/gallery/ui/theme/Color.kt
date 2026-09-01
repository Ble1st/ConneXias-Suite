package de.ble1st.gallery.ui.theme

import androidx.compose.ui.graphics.Color

// Statischer Fallback-Seed für API < 31 (kein Wallpaper-basiertes Dynamic Color verfügbar) und für
// jeden Nutzer, der Material You in den Systemeinstellungen abgeschaltet hat. Violett statt des
// Bernsteins von ConneXias Files oder des Türkis von ConneXias Kamera — eigenständige Markenfarbe
// je App der Suite.
val SeedPrimaryLight = Color(0xFF6552AE)
val SeedOnPrimaryLight = Color(0xFFFFFFFF)
val SeedPrimaryContainerLight = Color(0xFFE9DDFF)
val SeedOnPrimaryContainerLight = Color(0xFF20005A)

val SeedPrimaryDark = Color(0xFFCFBDFF)
val SeedOnPrimaryDark = Color(0xFF36207A)
val SeedPrimaryContainerDark = Color(0xFF4C3792)
val SeedOnPrimaryContainerDark = Color(0xFFE9DDFF)
