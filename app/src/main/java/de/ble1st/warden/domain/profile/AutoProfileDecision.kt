package de.ble1st.warden.domain.profile

/**
 * "Automatische Profilumschaltung" (2026-08-28, aus der Lückenanalyse) — bisher waren die drei
 * Profile rein manuell: kein Zeitplan, keine Bindung an eine Bedrohungslage, und trotz bereits
 * vorhandener `ThreatSeverity.CRITICAL`-Erkennung keine Stufe zwischen "nichts" und dem
 * Kiosk-Modus. Genau diese Zwischenstufe füllt diese Entscheidung.
 *
 * Rein rechnerisch, framework-frei: [AutoProfileController][de.ble1st.warden.profile
 * .AutoProfileController] liest Uhrzeit, Konfiguration und Bedrohungslage und wendet das Ergebnis
 * über `ConcordBus.applyProfile` an — hier steht nur, *welches* Profil gelten soll.
 *
 * **Bedrohungslage schlägt Zeitplan.** Ein kritischer Fund um 14 Uhr soll nicht dadurch entwertet
 * werden, dass das Tagesprofil "eigentlich" Alltag wäre. Umgekehrt schaltet die Zeitsteuerung
 * nicht zurück, solange der Fund besteht — erst wenn er verschwunden (behandelt oder als
 * Fehlalarm markiert) ist, greift wieder der Zeitplan.
 *
 * **Nie herunterstufen ohne Anlass:** hat die Besitzerin von Hand ein strengeres Profil gewählt
 * als der Zeitplan vorsieht, wird das nicht automatisch zurückgenommen — [evaluate] vergleicht
 * gegen das zuletzt *automatisch* gesetzte Profil, nicht gegen den aktuellen Ist-Zustand. Sonst
 * würde eine bewusste Verschärfung beim nächsten periodischen Lauf stillschweigend kassiert.
 */
object AutoProfileDecision {

    /**
     * @param config Soll-Konfiguration; deaktivierte Teile sind `null`.
     * @param minuteOfDay aktuelle Uhrzeit als Minuten seit Mitternacht (0..1439).
     * @param criticalFindingPresent ob der Verdachtsscanner gerade mindestens einen Fund der
     * Stufe `CRITICAL` führt.
     * @param lastAutoApplied zuletzt von dieser Automatik angewendetes Profil (`null` = noch nie).
     * @return anzuwendendes Profil oder `null`, wenn nichts zu tun ist.
     */
    fun evaluate(
        config: AutoProfileConfig,
        minuteOfDay: Int,
        criticalFindingPresent: Boolean,
        lastAutoApplied: WardenProfile?,
    ): WardenProfile? {
        val target = when {
            config.escalateOnCriticalThreat && criticalFindingPresent -> WardenProfile.MAXIMAL
            else -> scheduledProfile(config, minuteOfDay)
        } ?: return null
        return target.takeIf { it != lastAutoApplied }
    }

    /** `null`, solange die Zeitsteuerung aus ist oder für den aktuellen Abschnitt kein Profil
     * hinterlegt wurde (z. B. "nachts Maximal, tagsüber egal"). */
    private fun scheduledProfile(config: AutoProfileConfig, minuteOfDay: Int): WardenProfile? =
        if (isWithinNightWindow(config.nightStartMinuteOfDay, config.nightEndMinuteOfDay, minuteOfDay)) {
            config.nightProfile
        } else {
            config.dayProfile
        }

    /**
     * Das Nachtfenster läuft in aller Regel über Mitternacht (22:00–06:00) — ein naiver
     * `start <= t && t < end`-Vergleich wäre dann immer `false`. Start gleich Ende bedeutet
     * "kein Fenster", nicht "24 Stunden".
     */
    fun isWithinNightWindow(startMinuteOfDay: Int, endMinuteOfDay: Int, minuteOfDay: Int): Boolean = when {
        startMinuteOfDay == endMinuteOfDay -> false
        startMinuteOfDay < endMinuteOfDay -> minuteOfDay >= startMinuteOfDay && minuteOfDay < endMinuteOfDay
        else -> minuteOfDay >= startMinuteOfDay || minuteOfDay < endMinuteOfDay
    }
}

/**
 * @param nightProfile Profil innerhalb des Nachtfensters; `null` = Zeitsteuerung für die Nacht aus.
 * @param dayProfile Profil außerhalb; `null` = außerhalb des Fensters nichts umschalten.
 * @param escalateOnCriticalThreat bei kritischem Verdachtsfund auf `MAXIMAL` hochschalten.
 */
data class AutoProfileConfig(
    val nightProfile: WardenProfile?,
    val dayProfile: WardenProfile?,
    val nightStartMinuteOfDay: Int,
    val nightEndMinuteOfDay: Int,
    val escalateOnCriticalThreat: Boolean,
) {
    val isEnabled: Boolean
        get() = escalateOnCriticalThreat || nightProfile != null || dayProfile != null

    companion object {
        const val DEFAULT_NIGHT_START_MINUTE = 22 * 60
        const val DEFAULT_NIGHT_END_MINUTE = 6 * 60
    }
}
