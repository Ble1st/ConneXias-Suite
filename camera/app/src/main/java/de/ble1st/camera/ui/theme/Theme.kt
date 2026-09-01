package de.ble1st.camera.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Dieselbe stabile-`MaterialTheme`-Grundlage wie ConneXias Files (s. dortiges Theme.kt-Klassendoc
 * zur Begründung, warum kein `MaterialExpressiveTheme`) — spürbar runde Formen als Annäherung an
 * die Expressive-Formsprache, ohne auf die noch `internal`-markierten Expressive-Einstiegspunkte
 * angewiesen zu sein.
 */
private val ExpressiveLeaningShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val LightColors = lightColorScheme(
    primary = SeedPrimaryLight,
    onPrimary = SeedOnPrimaryLight,
    primaryContainer = SeedPrimaryContainerLight,
    onPrimaryContainer = SeedOnPrimaryContainerLight,
)

private val DarkColors = darkColorScheme(
    primary = SeedPrimaryDark,
    onPrimary = SeedOnPrimaryDark,
    primaryContainer = SeedPrimaryContainerDark,
    onPrimaryContainer = SeedOnPrimaryContainerDark,
)

@Composable
fun CameraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = ExpressiveLeaningShapes,
        content = content,
    )
}
