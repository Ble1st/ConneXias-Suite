package de.ble1st.warden.appmanagement

import android.content.Context
import androidx.core.content.edit

/**
 * "LockMode/Threat-Protection-Ausbau" (2026-08-25) — Baseline für
 * [de.ble1st.warden.domain.appmanagement.VersionDowngradeDecision]: der zuletzt gesehene
 * `versionCode` pro Paket. Dasselbe Speichermuster wie [SigningCertHistoryStore] (eine Zeile pro
 * Paket, `SharedPreferences`-Key = Paketname, Klartext reicht — ein verlorener Cache bedeutet nur
 * "nächster Downgrade wird als neue Baseline statt als Fund behandelt", kein Sicherheitsverlust),
 * hier als `Long` statt `String` (`versionCode`, s. [de.ble1st.warden.domain.appmanagement
 * .VersionDowngradeDecision]-Klassendoc zur 64-Bit-Begründung).
 */
class VersionHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun versionCodes(): Map<String, Long> =
        prefs.all.entries.mapNotNull { (pkg, value) -> (value as? Long)?.let { pkg to it } }.toMap()

    fun record(packageName: String, versionCode: Long) {
        prefs.edit { putLong(packageName, versionCode) }
    }

    private companion object {
        const val PREFS_NAME = "warden_version_history"
    }
}
