package de.ble1st.warden.registry

import android.content.Context
import androidx.core.content.edit

/**
 * Persistiert den **Soll**-Wert für [SupportMessageManager] — dieselbe Soll-vs-Ist-
 * Reconciliation-Begründung wie [WardenOrganizationNameStorage] (dortiges Klassendoc gilt
 * unverändert: Device-Protected-Storage statt normaler SharedPreferences, damit
 * [de.ble1st.warden.boot.RegistryReconciliationReceiver] ihn bei Boot-Drift erneut durchsetzen
 * kann; Klartext statt [de.ble1st.warden.crypto.EnvelopeFile], da der Wert ohnehin sichtbar auf
 * dem Gerät landet).
 */
object WardenSupportMessageStorage {
    private const val PREFS_NAME = "warden_support_message"
    private const val KEY_MESSAGE = "message"

    /** Großzügig, orientiert an [de.ble1st.warden.pin.WardenLockScreenTextStorage.MAX_LENGTH]. */
    const val MAX_LENGTH = 400

    fun load(context: Context): String? {
        val stored = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MESSAGE, null)
        return stored?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun save(context: Context, message: String?) {
        val normalized = message?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LENGTH)
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                if (normalized == null) remove(KEY_MESSAGE) else putString(KEY_MESSAGE, normalized)
            }
    }
}
