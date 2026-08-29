package de.ble1st.warden.cellsecurity

import android.content.Context
import androidx.core.content.edit
import de.ble1st.warden.domain.cellsecurity.CellGeneration
import de.ble1st.warden.domain.cellsecurity.CellObservation
import de.ble1st.warden.domain.cellsecurity.CellSecurityReaction

/**
 * Soll-Zustand und letzter Messwert für [CellSecurityController] (2026-08-29) — dasselbe Muster
 * wie [de.ble1st.warden.sim.SimChangeStorage], inklusive derselben Device-Protected-Storage-
 * Begründung (Prüflauf muss auch direkt nach einem Neustart greifen, vor der ersten Entsperrung).
 *
 * Der Messwert selbst (Zell-ID, LAC/TAC, Signalstärke) ist unverschlüsselt in SharedPreferences
 * abgelegt statt über [de.ble1st.warden.crypto.EnvelopeFile] — dieselbe Abwägung wie bei
 * [de.ble1st.warden.sim.SimChangeStorage]s gehashtem SIM-Fingerabdruck: es wird ohnehin nur auf
 * Gleichheit/Differenz verglichen, und die Werte sind für sich genommen kein schützenswertes
 * Geheimnis (kein Personenbezug über das hinaus, was jeder Mobilfunkbetreiber ohnehin kennt).
 */
object CellSecurityStorage {
    private const val PREFS_NAME = "warden_cell_security"
    private const val KEY_REACTION = "reaction"
    private const val KEY_OBSERVATION = "last_observation"
    private const val NULL_MARKER = "~"
    private const val FIELD_SEPARATOR = "|"

    /** `null` = Funktion aus. */
    fun loadReaction(context: Context): CellSecurityReaction? =
        prefs(context).getString(KEY_REACTION, null)
            ?.let { stored -> CellSecurityReaction.entries.firstOrNull { it.name == stored } }

    fun saveReaction(context: Context, reaction: CellSecurityReaction?) {
        prefs(context).edit {
            if (reaction == null) remove(KEY_REACTION) else putString(KEY_REACTION, reaction.name)
        }
    }

    /** `null` = noch nie erfolgreich gemessen, oder der gespeicherte Wert ließ sich nicht
     * parsen — in beide Fällen dieselbe sichere Reaktion: eine neue Baseline entsteht beim
     * nächsten erfolgreichen Messwert, statt an einem defekten alten Stand hängenzubleiben. */
    fun loadObservation(context: Context): CellObservation? =
        prefs(context).getString(KEY_OBSERVATION, null)?.let(::decode)

    fun saveObservation(context: Context, observation: CellObservation) {
        prefs(context).edit { putString(KEY_OBSERVATION, encode(observation)) }
    }

    /** Beim Ausschalten der Funktion: eine alte Baseline würde beim Wiedereinschalten sofort als
     * "Auffälligkeit" gelten, obwohl nur die Zwischenzeit unbeobachtet war. */
    fun clearObservation(context: Context) {
        prefs(context).edit { remove(KEY_OBSERVATION) }
    }

    private fun encode(o: CellObservation): String = listOf(
        o.mcc,
        o.mnc,
        o.cellId,
        o.areaCode,
        o.generation.name,
        o.signalDbm,
    ).joinToString(FIELD_SEPARATOR) { it?.toString() ?: NULL_MARKER }

    private fun decode(raw: String): CellObservation? {
        val parts = raw.split(FIELD_SEPARATOR)
        if (parts.size != 6) return null
        return try {
            CellObservation(
                mcc = parts[0].takeUnless { it == NULL_MARKER },
                mnc = parts[1].takeUnless { it == NULL_MARKER },
                cellId = parts[2].takeUnless { it == NULL_MARKER }?.toLong(),
                areaCode = parts[3].takeUnless { it == NULL_MARKER }?.toInt(),
                generation = CellGeneration.valueOf(parts[4]),
                signalDbm = parts[5].takeUnless { it == NULL_MARKER }?.toInt(),
            )
        } catch (e: IllegalArgumentException) {
            // Zahl-/Enum-Parse-Fehler bei einem manuell editierten oder aus einer künftigen
            // Version stammenden Prefs-Wert — kein Absturz, s. Klassendoc-Fußnote an [loadObservation].
            null
        }
    }

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
