package de.ble1st.gallery.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object MediaFormatters {

    /** Menschenlesbare Größenangabe (KB/MB/GB, Basis 1024) — 1:1 dieselbe Formel wie ConneXias
     * Files' `formatFileSize` (s. dortiger Klassendoc zur Begründung: Android-Konvention Basis
     * 1024 statt SI-1000, immer `Locale.US` für den Dezimalpunkt). */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        val exponent = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
        val value = bytes / 1024.0.pow(exponent.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[exponent - 1])
    }

    /** [dateSortMillis] ist bereits in Millisekunden — s. [de.ble1st.gallery.data.media.MediaItem]
     * `dateSortMillis`-Klassendoc (DATE_TAKEN mit DATE_ADDED-Fallback). */
    fun formatDate(dateSortMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(dateSortMillis))
}
