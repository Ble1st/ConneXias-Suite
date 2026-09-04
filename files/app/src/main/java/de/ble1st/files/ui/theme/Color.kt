package de.ble1st.files.ui.theme

import androidx.compose.ui.graphics.Color

// Statischer Fallback-Seed für API < 31 (kein Wallpaper-basiertes Dynamic Color verfügbar) und für
// jeden Nutzer, der Material You in den Systemeinstellungen abgeschaltet hat. Bernstein/Amber statt
// eines generischen Blau/Lila — assoziiert eher mit dem Ordner-Symbol klassischer Dateimanager als
// mit einem beliebigen App-Icon.
val SeedPrimaryLight = Color(0xFF8C5000)
val SeedOnPrimaryLight = Color(0xFFFFFFFF)
val SeedPrimaryContainerLight = Color(0xFFFFDCBE)
val SeedOnPrimaryContainerLight = Color(0xFF2E1500)

val SeedPrimaryDark = Color(0xFFFFB871)
val SeedOnPrimaryDark = Color(0xFF4A2800)
val SeedPrimaryContainerDark = Color(0xFF6A3C00)
val SeedOnPrimaryContainerDark = Color(0xFFFFDCBE)
