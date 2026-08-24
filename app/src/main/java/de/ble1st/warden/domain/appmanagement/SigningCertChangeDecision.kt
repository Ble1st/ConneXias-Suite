package de.ble1st.warden.domain.appmanagement

/**
 * Milestone "weitere Funktionen für den Sicherheitsscanner" (2026-08-22) — erkennt ein
 * Signatur-Zertifikat, das sich für ein bereits vorher gesehenes Paket zwischen zwei Scans
 * geändert hat (Update-Hijacking-Signal: derselbe Paketname, aber ein anderer Signer als beim
 * letzten Scan, wäre bei einem legitimen Play-Store-/Silent-Update nie der Fall).
 *
 * Nur Pakete, die schon *vorher* eine Baseline hatten ([previousFingerprints] enthält einen
 * Eintrag) und jetzt einen anderen Fingerprint tragen, zählen — ein frisch installiertes, bisher
 * unbekanntes Paket hat keine Baseline zum Vergleichen und ist deshalb nie ein "geändert"-Fund,
 * nur ein neuer Baseline-Eintrag (der Aufrufer,
 * `de.ble1st.warden.appmanagement.SuspiciousAppScanController`, persistiert [currentFingerprints]
 * nach jedem Aufruf).
 *
 * `fingerprint` selbst ist absichtlich ein reiner `String` statt eines eigenen Typs — die
 * konkrete Bildung (SHA-256 über die Signer-Zertifikate, s.
 * `de.ble1st.warden.appmanagement.SigningCertReader`) ist Android-Framework-Code, hier interessiert
 * nur "gleich oder verschieden".
 */
object SigningCertChangeDecision {

    fun evaluate(previousFingerprints: Map<String, String>, currentFingerprints: Map<String, String>): Set<String> =
        currentFingerprints
            .filter { (pkg, fingerprint) -> previousFingerprints[pkg]?.let { it != fingerprint } == true }
            .keys
}
