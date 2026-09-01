package de.ble1st.camera.ui.theme

import androidx.compose.ui.graphics.Color

// Statischer Fallback-Seed für API < 31 (kein Wallpaper-basiertes Dynamic Color verfügbar) und für
// jeden Nutzer, der Material You in den Systemeinstellungen abgeschaltet hat. Türkis/Teal statt
// des Bernsteins von ConneXias Files oder eines generischen Blau/Lila — eigenständige, dem
// klassischen Kamera-Blitz/Objektiv-Blau-Grün näherstehende Markenfarbe je App der Suite.
val SeedPrimaryLight = Color(0xFF00696D)
val SeedOnPrimaryLight = Color(0xFFFFFFFF)
val SeedPrimaryContainerLight = Color(0xFF9DF0F5)
val SeedOnPrimaryContainerLight = Color(0xFF002021)

val SeedPrimaryDark = Color(0xFF4DD9DE)
val SeedOnPrimaryDark = Color(0xFF00373A)
val SeedPrimaryContainerDark = Color(0xFF004F53)
val SeedOnPrimaryContainerDark = Color(0xFF9DF0F5)
