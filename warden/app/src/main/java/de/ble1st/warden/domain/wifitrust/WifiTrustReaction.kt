package de.ble1st.warden.domain.wifitrust

/**
 * Konfigurierbare Reaktion auf [WifiTrustDecision.Outcome.Untrusted] — dieselbe
 * "nullable enum, `null` = Feature aus"-Konvention wie
 * [de.ble1st.warden.domain.cellsecurity.CellSecurityReaction]/`SimChangeReaction`.
 *
 * Keine `NEUSTART`-Option (anders als bei Cell-Security/SIM-Wechsel): ein unbekanntes WLAN allein
 * ist ein deutlich schwächeres Signal als ein SIM-Tausch oder ein plausibler IMSI-Catcher-Fund —
 * viele legitime Situationen (Café, Flughafen, ein neuer Router zu Hause) erzeugen genau dasselbe
 * Ereignis. Ein Reboot ins BFU dafür wäre unverhältnismäßig; die schärfste verfügbare Reaktion ist
 * daher das bereits vorhandene Netz-Sperre-Feature selbst zu aktivieren, nicht das Gerät zu sperren.
 */
enum class WifiTrustReaction(val label: String) {
    NUR_MELDEN("Nur melden"),
    NETZWERK_SPERREN("Netz-Sperre aktivieren"),
}
