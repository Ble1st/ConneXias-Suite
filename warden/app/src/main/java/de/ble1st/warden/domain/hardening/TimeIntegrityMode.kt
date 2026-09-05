package de.ble1st.warden.domain.hardening

/**
 * Wie stark Warden die Systemuhr gegen Manipulation absichert (2026-09-05, Tier-2 der
 * DPC-Recherche).
 *
 * **Das Problem, das damit geschlossen wird:** Wardens Audit-Log ist hash-verkettet
 * ([de.ble1st.warden.logging.HashChainLogStore]) — die Kette beweist, dass Einträge weder
 * verändert noch umsortiert noch entfernt wurden. Sie beweist **nicht**, *wann* etwas passiert ist:
 * die Zeitstempel kommen aus `System.currentTimeMillis()`. Wer die Uhr zurückstellt, bekommt ein
 * formal einwandfrei verifizierbares Log mit falschen Zeiten — und ein exportiertes Log (s.
 * "Audit-Log-Export") ist damit als Beleg wertlos, ohne dass die Kettenprüfung das anzeigen würde.
 *
 * [NUR_AUTOMATISCH] setzt Netzzeit durch (die Uhr stimmt dann wieder), [AUTOMATISCH_UND_SPERREN]
 * nimmt zusätzlich die Einstellung aus der Hand (`DISALLOW_CONFIG_DATE_TIME` — der Safeguard
 * `CONFIG_DATE_TIME_DISABLED_ID` existiert dafür seit Längerem im Katalog; dieses Auswahlmenü
 * fasst beide Hälften zu einer verständlichen Entscheidung zusammen, statt sie über zwei
 * unabhängige Bildschirme zu verteilen).
 *
 * **Die zweite Hälfte gehört weiterhin dem Katalog-Safeguard, nicht diesem Enum.**
 * [de.ble1st.warden.hardening.HardeningPreferencesController] schaltet die Sperre deshalb über die
 * Registry ein und **nie** wieder aus: zwei unabhängige Soll-Zustände für dasselbe DPM-Bit sind in
 * diesem Projekt schon zweimal ein echter Fehler gewesen. Ein Wechsel zurück auf [AUS] oder
 * [NUR_AUTOMATISCH] lässt eine gesetzte Sperre also stehen — ausgeschaltet wird sie am Schalter im
 * Safeguards-Bildschirm, dort, wo ihr Soll-Zustand herkommt (der Feldtext sagt das auch so).
 *
 * **Grenze, die ehrlich benannt gehört:** Netzzeit kommt per NITZ vom Mobilfunknetz oder per NTP.
 * Beides ist gegen einen Angreifer mit Netzkontrolle (IMSI-Catcher, s.
 * [de.ble1st.warden.domain.cellsecurity.CellSecurityDecision]) nicht beweissicher. Das hier
 * schützt gegen den naheliegenden Fall — jemand stellt die Uhr in den Einstellungen zurück —, nicht
 * gegen einen Angreifer, der ohnehin schon das Netz kontrolliert.
 */
enum class TimeIntegrityMode(val label: String) {
    /** Warden fasst Zeit-Einstellungen nicht an. */
    AUS("Aus — Uhrzeit nicht beeinflussen"),

    /** Netzzeit und Netz-Zeitzone erzwingen, Einstellung bleibt änderbar. */
    NUR_AUTOMATISCH("Automatische Netzzeit erzwingen"),

    /** Zusätzlich das manuelle Ändern von Datum/Uhrzeit sperren. */
    AUTOMATISCH_UND_SPERREN("Netzzeit erzwingen und Änderung sperren"),
    ;

    val enforcesAutoTime: Boolean get() = this != AUS
    val locksSetting: Boolean get() = this == AUTOMATISCH_UND_SPERREN

    companion object {
        val DEFAULT: TimeIntegrityMode = AUS
    }
}
