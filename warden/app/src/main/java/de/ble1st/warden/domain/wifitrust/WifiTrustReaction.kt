package de.ble1st.warden.domain.wifitrust

/**
 * Konfigurierbare Reaktion auf [WifiTrustDecision.Outcome.Untrusted] — dieselbe
 * "nullable enum, `null` = Feature aus"-Konvention wie
 * [de.ble1st.warden.domain.cellsecurity.CellSecurityReaction]/`SimChangeReaction`.
 *
 * **`NEUSTART` ergänzt (2026-09-05, Nutzerwunsch).** Ursprünglich bewusst weggelassen (s. Historie
 * unten) — ein unbekanntes WLAN allein ist tatsächlich ein schwächeres Signal als ein SIM-Tausch,
 * und viele legitime Situationen (Café, Flughafen, neuer Router zu Hause) erzeugen dasselbe
 * Ereignis. Die Abwägung bleibt also gültig, nur nicht mehr als struktureller Ausschluss: wer die
 * schärfste Stufe trotzdem will (z. B. ein Gerät, das nur an genau einem vertrauten Ort betrieben
 * werden soll), kann sie jetzt bewusst wählen, genau wie bei Cell-Security/SIM-Wechsel — `NUR_MELDEN`
 * bleibt der Default beim Einschalten.
 */
enum class WifiTrustReaction(val label: String) {
    NUR_MELDEN("Nur melden"),
    NETZWERK_SPERREN("Netz-Sperre aktivieren"),

    /** Zusätzlich `DevicePolicyManager.reboot()` — derselbe Reflex wie
     * [de.ble1st.warden.domain.cellsecurity.CellSecurityReaction.NEUSTART]/
     * [de.ble1st.warden.domain.sim.SimChangeReaction.NEUSTART]. Läuft über
     * [de.ble1st.warden.domain.presence.DestructiveCommandGuard], genau wie die beiden anderen
     * lokalen Reboot-Auslöser. */
    NEUSTART("Neustart (BFU)"),
}
