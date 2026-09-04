package de.ble1st.warden.wifitrust

import android.content.Context
import androidx.core.content.edit
import de.ble1st.warden.domain.wifitrust.WifiTrustReaction

/**
 * Soll-Zustand für [WifiTrustController] (2026-09-03) — dasselbe Muster wie
 * [de.ble1st.warden.cellsecurity.CellSecurityStorage], inklusive derselben Device-Protected-
 * Storage-Begründung (der periodische Prüflauf soll auch direkt nach einem Neustart greifen,
 * bevor die erste Entsperrung stattfand).
 *
 * Die vertrauten SSIDs selbst sind — wie bei
 * [de.ble1st.warden.cellsecurity.CellSecurityStorage]s Messwert — kein schützenswertes Geheimnis
 * (jeder in Reichweite kann dieselben Netznamen ohnehin per WLAN-Scan sehen), deshalb reicht ein
 * unverschlüsselter `StringSet` statt [de.ble1st.warden.crypto.EnvelopeFile].
 */
object WifiTrustStorage {
    private const val PREFS_NAME = "warden_wifi_trust"
    private const val KEY_REACTION = "reaction"
    private const val KEY_TRUSTED_SSIDS = "trusted_ssids"

    /** `null` = Funktion aus. */
    fun loadReaction(context: Context): WifiTrustReaction? =
        prefs(context).getString(KEY_REACTION, null)
            ?.let { stored -> WifiTrustReaction.entries.firstOrNull { it.name == stored } }

    fun saveReaction(context: Context, reaction: WifiTrustReaction?) {
        prefs(context).edit {
            if (reaction == null) remove(KEY_REACTION) else putString(KEY_REACTION, reaction.name)
        }
    }

    fun loadTrustedSsids(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_TRUSTED_SSIDS, emptySet()).orEmpty()

    fun addTrustedSsid(context: Context, ssid: String) {
        val normalized = ssid.trim()
        if (normalized.isEmpty()) return
        val updated = loadTrustedSsids(context) + normalized
        prefs(context).edit { putStringSet(KEY_TRUSTED_SSIDS, updated) }
    }

    fun removeTrustedSsid(context: Context, ssid: String) {
        val updated = loadTrustedSsids(context) - ssid
        prefs(context).edit { putStringSet(KEY_TRUSTED_SSIDS, updated) }
    }

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
