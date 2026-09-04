package de.ble1st.files.util

import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * Menschenlesbare Größenangabe (KB/MB/GB, Basis 1024 — wie der Rest des Android-Systems, nicht
 * die SI-Basis-1000-Variante, damit die angezeigte Zahl mit dem übereinstimmt, was
 * Android-Einstellungen/andere Dateimanager für dieselbe Datei anzeigen).
 *
 * Immer mit `Locale.US` formatiert (Dezimalpunkt statt -komma) — die Einheiten "KB"/"MB"/… bleiben
 * ohnehin unlokalisierte englische Abkürzungen; ein lokalisiertes Komma nur bei der Zahl davor
 * (`Locale.getDefault()`) wäre eine inkonsistente Mischung aus beidem.
 */
fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    val exponent = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    val value = bytes / 1024.0.pow(exponent.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[exponent - 1])
}
