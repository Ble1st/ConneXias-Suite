package de.ble1st.warden.appmanagement

import android.content.Context
import androidx.core.content.edit

/**
 * "Permission-Diff bei App-Updates" (2026-09-03) — Baseline für [de.ble1st.warden.domain
 * .appmanagement.PermissionEscalationDecision]: die zuletzt gesehene Menge gefährlicher
 * Permissions pro Paket. Dasselbe Speichermuster wie [VersionHistoryStore]/
 * [SigningCertHistoryStore] (`SharedPreferences`-Key = Paketname, Klartext reicht — Permission-
 * Namen sind kein Geheimnis, dieselbe Einstufung wie `RevokedPermissionStore`; ein verlorener
 * Cache bedeutet nur "nächste Eskalation wird als neue Baseline statt als Fund behandelt", kein
 * Sicherheitsverlust), hier als `StringSet` statt eines Einzelwerts.
 */
class PermissionHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun dangerousPermissions(): Map<String, Set<String>> =
        prefs.all.entries.mapNotNull { (pkg, value) -> (value as? Set<*>)?.let { pkg to it.filterIsInstance<String>().toSet() } }.toMap()

    fun record(packageName: String, dangerousPermissions: Set<String>) {
        prefs.edit { putStringSet(packageName, dangerousPermissions) }
    }

    private companion object {
        const val PREFS_NAME = "warden_permission_history"
    }
}
