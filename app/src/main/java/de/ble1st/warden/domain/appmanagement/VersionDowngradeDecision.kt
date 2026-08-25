package de.ble1st.warden.domain.appmanagement

/**
 * "LockMode/Threat-Protection-Ausbau" (2026-08-25, angelehnt an Feature-Ideenliste Punkt 50
 * "Rollback-Schutz") — dieselbe Struktur wie [SigningCertChangeDecision]: erkennt ein
 * `versionCode`, das für ein bereits vorher gesehenes Paket zwischen zwei Scans *gesunken* ist.
 *
 * Nur Pakete, die schon *vorher* eine Baseline hatten ([previousVersionCodes] enthält einen
 * Eintrag) und jetzt einen niedrigeren `versionCode` tragen, zählen — ein frisch installiertes,
 * bisher unbekanntes Paket hat keine Baseline zum Vergleichen und ist deshalb nie ein
 * "zurückgestuft"-Fund, nur ein neuer Baseline-Eintrag (der Aufrufer,
 * `de.ble1st.warden.appmanagement.SuspiciousAppScanController`, persistiert
 * [currentVersionCodes] nach jedem Aufruf). Ein *gleicher* oder *höherer* `versionCode` (normales
 * Update) zählt ausdrücklich nicht.
 *
 * `versionCode` bewusst als `Long` (`PackageInfo.getLongVersionCode()`, seit API 28 die
 * empfohlene 64-Bit-Form — die alte `Int`-`versionCode`-Property ist seitdem deprecated und
 * bildet nur noch die unteren 32 Bit ab).
 */
object VersionDowngradeDecision {

    fun evaluate(previousVersionCodes: Map<String, Long>, currentVersionCodes: Map<String, Long>): Set<String> =
        currentVersionCodes
            .filter { (pkg, versionCode) -> previousVersionCodes[pkg]?.let { versionCode < it } == true }
            .keys
}
