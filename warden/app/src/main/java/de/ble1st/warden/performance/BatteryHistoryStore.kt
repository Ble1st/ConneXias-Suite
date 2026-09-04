package de.ble1st.warden.performance

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

/**
 * Performance-Monitoring-Fenster (2026-08-25) — Ringpuffer der letzten [MAX_SAMPLES]
 * Batterie-Messpunkte (`de.ble1st.warden.performance.BatterySamplingWorker` hängt periodisch
 * einen neuen an), Grundlage für `de.ble1st.warden.domain.performance.BatteryDrainDecision`.
 * Ein Klartext-JSON-Array in `SharedPreferences` genügt (`org.json`, Teil der Android-Plattform,
 * keine zusätzliche Abhängigkeit) — dieselbe "verlorener Cache ist unkritisch"-Begründung wie
 * [de.ble1st.warden.appmanagement.SigningCertHistoryStore]: ein zurückgesetzter Verlauf bedeutet
 * höchstens vorübergehend "keine Drain-Rate anzeigbar", kein Sicherheitsverlust.
 *
 * [recentUnchargedSamples] filtert genau die zusammenhängende Nicht-Lade-Phase am Ende des
 * Verlaufs heraus (jüngste Probe rückwärts, bis entweder eine ladende Probe oder das Ende der
 * Liste erreicht ist) — ein Ladevorgang mittendrin würde die Differenz sonst als "kaum Entladung"
 * verfälschen, obwohl das Gerät zwischenzeitlich am Netz hing.
 */
class BatteryHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(timestampMillis: Long, percent: Int, charging: Boolean) {
        val samples = allSamples().toMutableList()
        samples += Sample(timestampMillis, percent, charging)
        val bounded = samples.takeLast(MAX_SAMPLES)
        val array = JSONArray()
        for (sample in bounded) {
            val entry = JSONArray()
            entry.put(sample.timestampMillis)
            entry.put(sample.percent)
            entry.put(sample.charging)
            array.put(entry)
        }
        prefs.edit { putString(KEY_SAMPLES, array.toString()) }
    }

    /** Aufsteigend nach Zeitstempel, genau die jüngste zusammenhängende Nicht-Lade-Phase — s.
     * Klassendoc. Leer, wenn die jüngste Probe selbst schon ladend ist (keine aktuelle
     * Entladungs-Phase zum Berechnen). */
    fun recentUnchargedSamples(): List<Pair<Long, Int>> {
        val samples = allSamples()
        if (samples.isEmpty() || samples.last().charging) return emptyList()
        val tail = samples.asReversed().takeWhile { !it.charging }.asReversed()
        return tail.map { it.timestampMillis to it.percent }
    }

    private fun allSamples(): List<Sample> {
        val raw = prefs.getString(KEY_SAMPLES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val entry = array.getJSONArray(i)
                Sample(entry.getLong(0), entry.getInt(1), entry.getBoolean(2))
            }
        }.getOrDefault(emptyList())
    }

    private data class Sample(val timestampMillis: Long, val percent: Int, val charging: Boolean)

    private companion object {
        const val PREFS_NAME = "warden_battery_history"
        const val KEY_SAMPLES = "samples"
        const val MAX_SAMPLES = 96 // ~2 Tage bei 30-Minuten-Intervall.
    }
}
