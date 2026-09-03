package de.ble1st.files.ui.browser

import android.content.Context
import androidx.core.content.edit

enum class ViewMode { LIST, GRID }

/**
 * Reine UI-Präferenz (kein Datenmodell wie `TrashStore`/`WebDavAccountStore`) — ein einzelner Wert
 * rechtfertigt nicht das StateFlow-Cache-Muster der übrigen Stores, ein direktes SharedPreferences-
 * Lesen/Schreiben genügt. Global statt pro Ordner gespeichert: der Nutzer erwartet dieselbe
 * Ansicht überall, kein Ordner-für-Ordner-Umschalten.
 */
object ViewModePreference {
    private const val PREFS_FILE = "ui_prefs"
    private const val KEY_VIEW_MODE = "browser_view_mode"

    fun get(context: Context): ViewMode {
        val raw = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).getString(KEY_VIEW_MODE, null)
        return raw?.let { name -> runCatching { ViewMode.valueOf(name) }.getOrNull() } ?: ViewMode.LIST
    }

    fun set(context: Context, mode: ViewMode) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit { putString(KEY_VIEW_MODE, mode.name) }
    }
}
