package de.ble1st.warden.pin

import android.content.Context
import androidx.core.content.edit
import de.ble1st.warden.domain.pin.LockdownTriggerProfile

/**
 * "Lockdown-Auslöse-Profil" (2026-08-27) — dieselbe minimale Klartext-`SharedPreferences`-
 * Begründung wie [WardenLockTaskAutoEngageStore]: reine UI-Einstellung, kein Sicherheitsverlust
 * bei Zurücksetzen auf den Default. Default [LockdownTriggerProfile.STANDARD] — weder das
 * strengste (STRICT, keine Schnellauslöser) noch das schnellste (FAST, keine Rückfrage)
 * Verhalten, damit ein frisch eingerichtetes Gerät weder überraschend leicht noch überraschend
 * schwer auslösbar ist.
 */
object LockdownTriggerProfileStore {
    private const val PREFS_NAME = "warden_lockdown_trigger_profile"
    private const val KEY_PROFILE = "profile"

    fun load(context: Context): LockdownTriggerProfile {
        val stored = prefs(context).getString(KEY_PROFILE, null) ?: return LockdownTriggerProfile.STANDARD
        return runCatching { LockdownTriggerProfile.valueOf(stored) }.getOrDefault(LockdownTriggerProfile.STANDARD)
    }

    fun save(context: Context, profile: LockdownTriggerProfile) {
        prefs(context).edit { putString(KEY_PROFILE, profile.name) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
