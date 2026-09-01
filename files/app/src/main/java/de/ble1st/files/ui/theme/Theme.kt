package de.ble1st.files.ui.theme

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
 * "Material 3 Expressive" ist zum Zeitpunkt dieses Projektstarts nur teilweise öffentlich: die
 * dedizierten Expressive-Einstiegspunkte (`MaterialExpressiveTheme`, `expressiveLightColorScheme`,
 * `MotionScheme.expressive()`, ein FAB-Menü) sind im aktuell auflösbaren `material3` (1.4.0, auch
 * über das neueste Compose-BOM) noch als `internal` markiert und daher aus App-Code nicht
 * aufrufbar (geprüft direkt an den Sources-Jars, nicht nur vermutet). Diese Datei baut deshalb
 * bewusst auf der stabilen `MaterialTheme`-API auf, mit spürbar runden Formen als Annäherung an
 * die Expressive-Formsprache.
 *
 * Sobald eine künftige material3-Version diese Einstiegspunkte public macht, reicht ein BOM-Bump
 * plus Austausch von `MaterialTheme(...)` gegen `MaterialExpressiveTheme(...)` genau hier — der
 * Rest der App kennt nur [FilesTheme], nie MaterialTheme direkt.
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
fun FilesTheme(
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
