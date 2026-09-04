package de.ble1st.warden.sim

import android.content.Context
import androidx.core.content.edit
import de.ble1st.warden.domain.sim.SimChangeReaction

/**
 * Soll-Zustand und Baseline-Fingerabdruck für [SimChangeController] (2026-08-28).
 *
 * Device-Protected Storage aus demselben Grund wie
 * [de.ble1st.warden.failedattempts.FailedAttemptsRebootStorage]: der Prüflauf soll auch direkt
 * nach einem Neustart greifen können, ohne auf die erste Entsperrung zu warten — genau dann ist
 * der Tausch am wahrscheinlichsten passiert. Gespeichert wird ohnehin nur ein Hash, kein
 * Klartext-SIM-Merkmal (s. [SimFingerprintReader]).
 */
object SimChangeStorage {
    private const val PREFS_NAME = "warden_sim_change"
    private const val KEY_REACTION = "reaction"
    private const val KEY_BASELINE = "baseline_fingerprint"

    /** `null` = Funktion aus. */
    fun loadReaction(context: Context): SimChangeReaction? =
        prefs(context).getString(KEY_REACTION, null)
            ?.let { stored -> SimChangeReaction.entries.firstOrNull { it.name == stored } }

    fun saveReaction(context: Context, reaction: SimChangeReaction?) {
        prefs(context).edit {
            if (reaction == null) remove(KEY_REACTION) else putString(KEY_REACTION, reaction.name)
        }
    }

    /** `null` = noch nie erfolgreich gemessen. */
    fun loadBaseline(context: Context): String? = prefs(context).getString(KEY_BASELINE, null)

    fun saveBaseline(context: Context, fingerprint: String) {
        prefs(context).edit { putString(KEY_BASELINE, fingerprint) }
    }

    /** Beim Ausschalten der Funktion: eine alte Baseline würde beim Wiedereinschalten sofort als
     * "Wechsel" gelten, obwohl nur die Zwischenzeit unbeobachtet war. */
    fun clearBaseline(context: Context) {
        prefs(context).edit { remove(KEY_BASELINE) }
    }

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
