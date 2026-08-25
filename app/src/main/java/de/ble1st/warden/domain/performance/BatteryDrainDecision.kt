package de.ble1st.warden.domain.performance

/**
 * Performance-Monitoring-Fenster (2026-08-25, "Battery-Drain-Analyse"). Reine Berechnung, kein
 * Android-Bezug — `de.ble1st.warden.performance.BatteryHistoryStore` liefert die Rohdaten
 * (Zeitstempel + Prozent, bereits auf zusammenhängende Nicht-Lade-Phasen vorgefiltert).
 *
 * `samples` müssen aufsteigend nach Zeitstempel sortiert sein und **dieselbe** Nicht-Lade-Phase
 * beschreiben (der Aufrufer filtert das vor, s. `BatteryHistoryStore.recentUnchargedSamples`) —
 * diese Funktion selbst kennt "Laden" nicht, sie berechnet nur die Differenz zwischen dem ersten
 * und letzten übergebenen Punkt. `null`, wenn weniger als zwei Punkte vorliegen oder keine
 * messbare Zeit vergangen ist (Divison durch Null vermieden).
 */
object BatteryDrainDecision {

    /** Positiv = Entladung (üblicher Fall), negativ würde eine Zunahme ohne erkanntes Laden
     * bedeuten (sollte durch die Vorfilterung praktisch nie vorkommen). */
    fun percentPerHour(samples: List<Pair<Long, Int>>): Double? {
        if (samples.size < 2) return null
        val (firstTimestamp, firstPercent) = samples.first()
        val (lastTimestamp, lastPercent) = samples.last()
        val elapsedHours = (lastTimestamp - firstTimestamp) / 3_600_000.0
        if (elapsedHours <= 0.0) return null
        return (firstPercent - lastPercent) / elapsedHours
    }
}
