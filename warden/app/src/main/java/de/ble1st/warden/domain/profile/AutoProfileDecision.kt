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
 * **Nie herunterstufen ohne Anlass — Korrektur 2026-08-28 (aus der Code-/Sicherheitsanalyse,
 * Befund Q-1).** Diese Zusage stand schon vorher hier, wurde aber nicht eingehalten: der
 * Vergleich lief allein gegen `lastAutoApplied`, und genau das sieht eine manuelle Verschärfung
 * nicht. Beispiel: zuletzt automatisch stand Alltag, mittags schaltet die Besitzerin von Hand auf
 * Maximal, um 22:00 berechnet der Nachtlauf sein Nachtprofil, das ist ≠ Alltag — und
 * `WardenProfileApplier.apply()` revertiert jeden Safeguard, der nicht im Zielprofil steht,
 * schaltet Maximal also **herunter**. Dasselbe beim Wegfall eines kritischen Fundes.
 *
 * Deshalb kennt [evaluate] jetzt zwei Zustände statt einem:
 *
 *  - `lastAutoApplied` — was diese Automatik zuletzt gesetzt hat. Verhindert wie bisher, dass
 *    alle 15 Minuten dasselbe Profil erneut angewendet wird, und dass die Automatik eine bewusste
 *    manuelle Abschwächung gegen den Willen der Besitzerin wieder hochzieht.
 *  - `effectiveProfile` — welches Profil zuletzt *überhaupt* angewendet wurde, egal ob von Hand
 *    oder automatisch. Gepflegt wird das strukturell in `ConcordBus.applyProfile`, also an der
 *    einen Stelle, durch die beide Wege ohnehin müssen — nicht per Konvention an den Aufrufern.
 *
 * Die Regel darüber: **abschwächen darf die Automatik nur das, was sie selbst gesetzt hat.**
 * Verschärfen darf sie immer (die sichere Richtung — ein kritischer Fund soll auch über eine
 * manuelle Lockerung hinweg greifen). Der reguläre Zeitplan bleibt davon unberührt: läuft
 * nachts Maximal und morgens Alltag, ist das Herunterschalten erlaubt, weil das wirkende Maximal
 * genau das ist, was die Automatik nachts selbst gesetzt hat.
 *
 * **Bewusste Grenze:** hat die Besitzerin einzelne Safeguards *außerhalb* eines Profils
 * angeschaltet, nimmt jede Profilanwendung sie zurück — auch eine manuelle. Das liegt in der
 * Natur eines Profils ("genau diese Menge gilt", s. `WardenProfileApplier`) und ist hier nicht
 * abgedeckt; die Ordnung oben kennt nur Profile.
 */
object AutoProfileDecision {

    /**
     * @param config Soll-Konfiguration; deaktivierte Teile sind `null`.
     * @param minuteOfDay aktuelle Uhrzeit als Minuten seit Mitternacht (0..1439).
     * @param criticalFindingPresent ob der Verdachtsscanner gerade mindestens einen Fund der
     * Stufe `CRITICAL` führt.
     * @param lastAutoApplied zuletzt von dieser Automatik angewendetes Profil (`null` = noch nie).
     * @param effectiveProfile zuletzt überhaupt angewendetes Profil, manuell wie automatisch
     * (`null` = seit der Installation keins) — s. Klassendoc.
     * @return anzuwendendes Profil oder `null`, wenn nichts zu tun ist.
     */
    fun evaluate(
        config: AutoProfileConfig,
        minuteOfDay: Int,
        criticalFindingPresent: Boolean,
        lastAutoApplied: WardenProfile?,
        effectiveProfile: WardenProfile?,
    ): WardenProfile? {
        val target = when {
            config.escalateOnCriticalThreat && criticalFindingPresent -> WardenProfile.MAXIMAL
            else -> scheduledProfile(config, minuteOfDay)
        } ?: return null
        if (target == lastAutoApplied) return null
        if (wouldWeakenForeignHardening(target, lastAutoApplied, effectiveProfile)) return null
        return target
    }

    /**
     * Kern der Korrektur von Q-1: `true`, wenn [target] schwächer wäre als das gerade wirkende
     * Profil **und** dieses nicht von der Automatik selbst stammt. `effectiveProfile == null`
     * (noch nie ein Profil angewendet) ist kein Schutzfall — dann gibt es keine fremde Härtung,
     * die verloren gehen könnte.
     */
    private fun wouldWeakenForeignHardening(
        target: WardenProfile,
        lastAutoApplied: WardenProfile?,
        effectiveProfile: WardenProfile?,
    ): Boolean {
        if (effectiveProfile == null) return false
        if (effectiveProfile == lastAutoApplied) return false
        return target.strength < effectiveProfile.strength
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
