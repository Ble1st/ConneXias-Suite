package de.ble1st.warden.domain.wifitrust

/**
 * "WLAN-Vertrauensliste" (2026-09-03, Ideenliste Punkt 5 aus dem ClipboardGuard-Folgegespräch) —
 * dieselbe Grundidee wie [de.ble1st.warden.domain.cellsecurity.CellSecurityDecision], aber ohne
 * dessen Baseline-Vergleich: statt "hat sich seit dem letzten Messwert etwas verändert" prüft
 * dieser Vergleich direkt gegen eine vom Nutzer geführte Liste bekannter/vertrauenswürdiger SSIDs
 * — ein unbekanntes Netz ist sofort ein Fund, nicht erst nach einer zweiten Beobachtung.
 *
 * Reine SSID-Prüfung, kein BSSID-Abgleich — ein SSID-Spoof (ein Angreifer baut einen eigenen
 * Access Point mit dem Namen eines vertrauten Netzes) wird dadurch nicht erkannt. Das ist eine
 * bewusste Vereinfachung für die erste Version, keine verifizierte Sicherheitsgarantie — dieselbe
 * "Heuristik, kein Beweis"-Ehrlichkeit wie bei [de.ble1st.warden.domain.cellsecurity
 * .CellSecurityDecision].
 */
object WifiTrustDecision {

    sealed class Outcome {
        /** Kein WLAN verbunden, oder die SSID war nicht auslesbar (z. B. Standortzugriff/-dienst
         * aus — `WifiCurrentSsidReader` liefert dafür `null`, nicht "unbekanntes Netz"). */
        data object NotConnected : Outcome()
        data class Trusted(val ssid: String) : Outcome()
        data class Untrusted(val ssid: String) : Outcome()
    }

    fun evaluate(currentSsid: String?, trustedSsids: Set<String>): Outcome = when {
        currentSsid == null -> Outcome.NotConnected
        currentSsid in trustedSsids -> Outcome.Trusted(currentSsid)
        else -> Outcome.Untrusted(currentSsid)
    }
}
