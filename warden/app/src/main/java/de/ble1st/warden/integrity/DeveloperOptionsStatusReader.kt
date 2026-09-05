package de.ble1st.warden.integrity

import android.content.Context
import android.provider.Settings

/**
 * Milestone "weitere Funktionen für den Sicherheitsscanner" (2026-08-22, Feature 9) — liest den
 * ADB-/Entwickleroptionen-Status als zusätzlichen Geräte-Integritäts-Indikator neben
 * [DebuggableOsStatusReader] (dort: ist das *OS-Image* selbst ein Debug-Build; hier: hat der
 * Nutzer/eine andere App auf einem regulären Build die *Einstellung* aktiviert). Beides zusammen
 * ergibt ein vollständigeres Bild — ein `user`-Build mit eingeschaltetem ADB-Debugging ist
 * ebenfalls eine Abweichung vom beabsichtigten, gehärteten Zustand.
 *
 * Reiner Lesezugriff auf `Settings.Global` — keine Sonderberechtigung nötig, dieselben Werte sind
 * auch über `adb shell settings get global adb_enabled` einsehbar.
 */
class DeveloperOptionsStatusReader(private val context: Context) {

    fun isAdbEnabled(): Boolean = readSettingSafe(Settings.Global.ADB_ENABLED)

    fun isDeveloperOptionsEnabled(): Boolean = readSettingSafe(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)

    /** Fail-Safe: wenn der Settings-Schlüssel fehlt (z. B. manipulierte Settings-Datenbank,
     *  Custom-ROM), behandeln wir das als "aktiviert" — ein fehlender Schlüssel ist kein Beweis
     *  für "inaktiv", und ein False-Negative ("ADB inaktiv" obwohl es läuft) ist sicherheits-
     *  kritischer als ein False-Positive ("ADB aktiv" obwohl der Schlüssel fehlt). */
    private fun readSettingSafe(key: String): Boolean =
        try {
            Settings.Global.getInt(context.contentResolver, key) == 1
        } catch (_: Settings.SettingNotFoundException) {
            true
        }
}
