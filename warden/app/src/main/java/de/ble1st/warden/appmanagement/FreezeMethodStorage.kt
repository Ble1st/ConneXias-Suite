package de.ble1st.warden.appmanagement

import android.content.Context
import androidx.core.content.edit
import de.ble1st.warden.domain.appmanagement.FreezeMethod

/**
 * Soll-Zustand für [de.ble1st.warden.domain.appmanagement.FreezeMethod] (2026-09-05) — einfache
 * Klartext-`SharedPreferences` mit derselben Begründung wie
 * [de.ble1st.warden.pin.LockdownTriggerProfileStore]: reine Verhaltenseinstellung, kein Geheimnis,
 * und ein Zurückfallen auf den Default ([FreezeMethod.AUTOMATIK]) ist kein Sicherheitsverlust —
 * es ist genau das Verhalten, das Warden vor Einführung dieser Auswahl ohnehin hatte.
 *
 * Bewusst **nicht** device-protected: der Einfrier-Pfad läuft ausschließlich aus der UI bzw. aus
 * dem Verdachtsscanner heraus, beides erst nach der ersten Entsperrung.
 */
object FreezeMethodStorage {
    private const val PREFS_NAME = "warden_freeze_method"
    private const val KEY_METHOD = "method"

    fun load(context: Context): FreezeMethod {
        val stored = prefs(context).getString(KEY_METHOD, null) ?: return FreezeMethod.DEFAULT
        return runCatching { FreezeMethod.valueOf(stored) }.getOrDefault(FreezeMethod.DEFAULT)
    }

    fun save(context: Context, method: FreezeMethod) {
        prefs(context).edit { putString(KEY_METHOD, method.name) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
