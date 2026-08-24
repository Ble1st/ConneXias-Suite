package de.ble1st.warden.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.edit

/**
 * Wardens eigenes Compose-Theme — bewusst dunkel/schwarz mit einem wählbaren "Phosphor"-Akzent
 * ("Terminal-Feeling"), statt des bisherigen nackten `MaterialTheme { }` (Compose-Baseline-Lila,
 * s. UI-Review 2026-08-21). Ersetzt **nicht** das alte `res/values/themes.xml` (`Theme.Warden`,
 * seit Architektur-Review 2026-08-24/F-6 ein reines Plattform-`NoActionBar`-Theme statt des
 * vorherigen `Theme.MaterialComponents.DayNight.DarkActionBar`) — das bleibt als reines
 * Fenster-/Splash-Theme vor dem ersten Compose-Frame bestehen, hat mit dieser Datei nichts zu
 * tun.
 *
 * **Nur ein dunkles ColorScheme, kein Hell/Dunkel-Umschalter:** die App ist Device-Owner-
 * Sicherheitswerkzeug, kein Alltagsbegleiter — ein durchgängig dunkles "Terminal" passt zum
 * Charakter (PIN-Eingabe, Log-Ansicht, destruktive Kommandos) und erspart ein zweites,
 * separat zu pflegendes helles Schema. [WardenAccent] deckt die "frei wählbar"-Anforderung
 * stattdessen über die Akzentfarbe ab (klassische Phosphor-Monitor-Farben), nicht über
 * Hell/Dunkel.
 */
enum class WardenAccent(val label: String, val color: Color, val dim: Color) {
    GRUEN("Terminal-Grün", Color(0xFF33FF66), Color(0xFF1FAA45)),
    BERNSTEIN("Bernstein", Color(0xFFFFB000), Color(0xFFB37A00)),
    CYAN("Cyan", Color(0xFF4DE0FF), Color(0xFF2AA3BF)),
    PHOSPHOR_WEISS("Phosphor-Weiß", Color(0xFFE8E8E8), Color(0xFFAAAAAA)),
}

private val TerminalBackground = Color(0xFF0B0F0C)
private val TerminalSurface = Color(0xFF10150F)
private val TerminalSurfaceVariant = Color(0xFF1B241B)
private val TerminalOutline = Color(0xFF2E3D2F)
private val TerminalOnSurface = Color(0xFFD8E8DA)
private val TerminalError = Color(0xFFFF5C5C)

/**
 * Baut ein volles M3-`ColorScheme` aus der Terminal-Grundpalette + [accent]. Container-/
 * On-Container-Farben werden aus der Akzentfarbe *abgeleitet* (`lerp` gegen Hintergrund/Weiß)
 * statt pro [WardenAccent]-Eintrag hart hinterlegt — ein neuer Akzent braucht dadurch nur Zeile
 * + Farbe, keine sechs weiteren Container-Werte von Hand abgestimmt.
 */
fun wardenColorScheme(accent: WardenAccent = WardenAccent.GRUEN): ColorScheme {
    val primaryContainer = lerp(TerminalBackground, accent.color, 0.22f)
    val onPrimaryContainer = lerp(accent.color, Color.White, 0.35f)
    val secondaryContainer = lerp(TerminalBackground, accent.dim, 0.18f)
    val errorContainer = lerp(TerminalBackground, TerminalError, 0.22f)
    val onErrorContainer = lerp(TerminalError, Color.White, 0.35f)

    return darkColorScheme(
        primary = accent.color,
        onPrimary = Color.Black,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = accent.dim,
        onSecondary = Color.Black,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onPrimaryContainer,
        tertiary = accent.dim,
        onTertiary = Color.Black,
        tertiaryContainer = secondaryContainer,
        onTertiaryContainer = onPrimaryContainer,
        background = TerminalBackground,
        onBackground = TerminalOnSurface,
        surface = TerminalSurface,
        onSurface = TerminalOnSurface,
        surfaceVariant = TerminalSurfaceVariant,
        onSurfaceVariant = TerminalOnSurface.copy(alpha = 0.75f),
        surfaceContainer = TerminalSurface,
        surfaceContainerHigh = lerp(TerminalSurface, Color.White, 0.06f),
        surfaceContainerHighest = lerp(TerminalSurface, Color.White, 0.10f),
        surfaceContainerLow = lerp(TerminalBackground, TerminalSurface, 0.6f),
        surfaceContainerLowest = TerminalBackground,
        outline = TerminalOutline,
        outlineVariant = TerminalOutline.copy(alpha = 0.6f),
        error = TerminalError,
        onError = Color.Black,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
    )
}

private fun TextStyle.mono(): TextStyle = copy(fontFamily = FontFamily.Monospace)

/** Durchgängig Monospace statt nur punktuell — halbherzig gemischt (nur Headlines mono, Body
 * proportional) sieht auf Screens mit viel Fließtext (Scanner-Erklärung, Log-Einträge) wie ein
 * Stilbruch aus; Paketnamen/Hex-Strings (App-Verwaltung, Failsafe) profitieren ohnehin von
 * fester Zeichenbreite. Basiert auf der M3-Baseline-`Typography()`, nur die Font ersetzt —
 * Größen/Gewichte/Line-Heights bleiben die durchdachten M3-Defaults. */
val WardenTypography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.mono(),
        displayMedium = base.displayMedium.mono(),
        displaySmall = base.displaySmall.mono(),
        headlineLarge = base.headlineLarge.mono(),
        headlineMedium = base.headlineMedium.mono(),
        headlineSmall = base.headlineSmall.mono(),
        titleLarge = base.titleLarge.mono(),
        titleMedium = base.titleMedium.mono(),
        titleSmall = base.titleSmall.mono(),
        bodyLarge = base.bodyLarge.mono(),
        bodyMedium = base.bodyMedium.mono(),
        bodySmall = base.bodySmall.mono(),
        labelLarge = base.labelLarge.mono(),
        labelMedium = base.labelMedium.mono(),
        labelSmall = base.labelSmall.mono(),
    )
}

/** Einziger Einstiegspunkt für alle Activities — ersetzt das bisherige nackte
 * `MaterialTheme { … }` 1:1 (gleiche Signatur, gleicher `content`-Slot). */
@Composable
fun WardenTheme(accent: WardenAccent = WardenAccent.GRUEN, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = wardenColorScheme(accent),
        typography = WardenTypography,
        content = content,
    )
}

/**
 * Persistiert nur *welcher Akzent gewählt ist* — eine reine Darstellungspräferenz, kein
 * sicherheitsrelevanter Zustand (anders als Registry/Log/PIN-Blob) und deshalb bewusst über
 * einfaches `SharedPreferences` statt über [de.ble1st.warden.crypto.EnvelopeFile]: ein
 * manipulierter/verlorener Wert bedeutet im schlimmsten Fall "falsche Akzentfarbe angezeigt",
 * kein Fail-Safe-Fall.
 */
object WardenThemePrefs {
    private const val PREFS_NAME = "warden_theme"
    private const val KEY_ACCENT = "accent"

    fun load(context: Context): WardenAccent {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACCENT, null)
        return WardenAccent.entries.find { it.name == stored } ?: WardenAccent.GRUEN
    }

    fun save(context: Context, accent: WardenAccent) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_ACCENT, accent.name)
        }
    }
}
