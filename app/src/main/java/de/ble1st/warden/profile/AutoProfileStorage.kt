package de.ble1st.warden.profile

import android.content.Context
import androidx.core.content.edit
import de.ble1st.warden.domain.profile.AutoProfileConfig
import de.ble1st.warden.domain.profile.WardenProfile

/**
 * Soll-Konfiguration und zuletzt automatisch gesetztes Profil für [AutoProfileController]
 * (2026-08-28).
 *
 * Normale (credential-verschlüsselte) `SharedPreferences`, anders als bei SIM-Wechsel und
 * Fehlversuchs-Neustart: diese Automatik läuft ausschließlich über einen periodischen
 * WorkManager-Lauf, der vor der ersten Entsperrung nach einem Boot ohnehin nicht zuverlässig
 * arbeitet — dieselbe Begründung wie bei [de.ble1st.warden.autoreboot.AutoRebootStorage].
 */
object AutoProfileStorage {
    private const val PREFS_NAME = "warden_auto_profile"
    private const val KEY_NIGHT_PROFILE = "night_profile"
    private const val KEY_DAY_PROFILE = "day_profile"
    private const val KEY_NIGHT_START = "night_start_minute"
    private const val KEY_NIGHT_END = "night_end_minute"
    private const val KEY_ESCALATE = "escalate_on_critical"
    private const val KEY_LAST_APPLIED = "last_auto_applied"

    fun load(context: Context): AutoProfileConfig {
        val prefs = prefs(context)
        return AutoProfileConfig(
            nightProfile = prefs.getString(KEY_NIGHT_PROFILE, null)?.let(::profileOrNull),
            dayProfile = prefs.getString(KEY_DAY_PROFILE, null)?.let(::profileOrNull),
            nightStartMinuteOfDay = prefs.getInt(KEY_NIGHT_START, AutoProfileConfig.DEFAULT_NIGHT_START_MINUTE),
            nightEndMinuteOfDay = prefs.getInt(KEY_NIGHT_END, AutoProfileConfig.DEFAULT_NIGHT_END_MINUTE),
            escalateOnCriticalThreat = prefs.getBoolean(KEY_ESCALATE, false),
        )
    }

    fun save(context: Context, config: AutoProfileConfig) {
        prefs(context).edit {
            if (config.nightProfile == null) remove(KEY_NIGHT_PROFILE) else putString(KEY_NIGHT_PROFILE, config.nightProfile.name)
            if (config.dayProfile == null) remove(KEY_DAY_PROFILE) else putString(KEY_DAY_PROFILE, config.dayProfile.name)
            putInt(KEY_NIGHT_START, config.nightStartMinuteOfDay)
            putInt(KEY_NIGHT_END, config.nightEndMinuteOfDay)
            putBoolean(KEY_ESCALATE, config.escalateOnCriticalThreat)
        }
    }

    fun loadLastApplied(context: Context): WardenProfile? =
        prefs(context).getString(KEY_LAST_APPLIED, null)?.let(::profileOrNull)

    fun saveLastApplied(context: Context, profile: WardenProfile) {
        prefs(context).edit { putString(KEY_LAST_APPLIED, profile.name) }
    }

    /** Beim Abschalten der Automatik: sonst gälte beim Wiedereinschalten ein längst überholtes
     * Profil als "zuletzt automatisch gesetzt" und der erste Lauf täte nichts. */
    fun clearLastApplied(context: Context) {
        prefs(context).edit { remove(KEY_LAST_APPLIED) }
    }

    private fun profileOrNull(name: String): WardenProfile? =
        WardenProfile.entries.firstOrNull { it.name == name }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
