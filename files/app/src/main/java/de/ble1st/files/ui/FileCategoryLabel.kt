package de.ble1st.files.ui

import androidx.annotation.StringRes
import de.ble1st.files.R
import de.ble1st.files.util.FileCategory

/**
 * Klartext-Bezeichnung einer [FileCategory] für Vorlesehilfen.
 *
 * Gemeinsam von der lokalen Liste/Kachelansicht und dem WebDAV-Browser genutzt: das
 * Kategorie-Symbol ist dort die einzige Stelle, an der "Ordner" von "Datei" unterschieden wird —
 * der Dateiname sagt es nicht (Ordner haben keine Endung, endungslose Dateien aber auch nicht).
 */
@StringRes
fun categoryLabelRes(category: FileCategory): Int = when (category) {
    FileCategory.FOLDER -> R.string.content_desc_category_folder
    FileCategory.IMAGE -> R.string.content_desc_category_image
    FileCategory.VIDEO -> R.string.content_desc_category_video
    FileCategory.AUDIO -> R.string.content_desc_category_audio
    FileCategory.TEXT -> R.string.content_desc_category_text
    FileCategory.ARCHIVE -> R.string.content_desc_category_archive
    FileCategory.APK -> R.string.content_desc_category_apk
    FileCategory.OTHER -> R.string.content_desc_category_other
}
