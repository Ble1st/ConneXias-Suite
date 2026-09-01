package de.ble1st.warden.registry

import android.content.Context
import androidx.core.content.edit

/**
 * Persistiert den **Soll**-Wert für [OrganizationNameManager] — dieselbe Soll-vs-Ist-
 * Reconciliation-Begründung wie [de.ble1st.warden.pin.WardenLockScreenTextStorage] (dortiges
 * Klassendoc gilt hier unverändert: Device-Protected-Storage statt normaler SharedPreferences,
 * damit [de.ble1st.warden.boot.RegistryReconciliationReceiver] ihn bei Boot-Drift erneut
 * durchsetzen kann; Klartext statt [de.ble1st.warden.crypto.EnvelopeFile], weil der Wert ohnehin
 * sichtbar auf dem Gerät landet und ein verlorener Wert kein Fail-Safe-Fall ist).
 *
 * Eigene, von [de.ble1st.warden.pin.WardenLockScreenTextStorage] unabhängige Datei (getrennter
 * DPM-Wert, s. [OrganizationNameManager]-Klassendoc) — bewusst hier in `registry/` statt in
 * `pin/` abgelegt, direkt neben dem zugehörigen [OrganizationNameManager], statt der (historisch
 * gewachsenen, thematisch nicht ganz treffenden) `pin/`-Platzierung des Sperrbildschirm-Text-Caches
 * zu folgen.
 */
object WardenOrganizationNameStorage {
    private const val PREFS_NAME = "warden_organization_name"
    private const val KEY_NAME = "name"

    /** Willkürlich, aber großzügig genug für einen Organisationsnamen. */
    const val MAX_LENGTH = 100

    /** `null`, wenn nichts (oder nur Leerraum) hinterlegt ist. */
    fun load(context: Context): String? {
        val stored = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_NAME, null)
        return stored?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** `name = null` oder nur Leerraum löscht den Soll-Wert wieder. Länger als [MAX_LENGTH] wird
     * abgeschnitten statt abgelehnt — derselbe Grundsatz wie
     * [de.ble1st.warden.pin.WardenLockScreenTextStorage.save]. */
    fun save(context: Context, name: String?) {
        val normalized = name?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LENGTH)
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                if (normalized == null) remove(KEY_NAME) else putString(KEY_NAME, normalized)
            }
    }
}
