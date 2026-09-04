package de.ble1st.warden.domain.appmanagement

/**
 * "Permission-Diff bei App-Updates" (2026-09-03, auf Nutzerwunsch, Ideenliste Punkt 2 aus dem
 * ClipboardGuard-Folgegespräch) — dieselbe Struktur wie [VersionDowngradeDecision]/
 * [SigningCertChangeDecision]: erkennt, dass ein bereits vorher gesehenes Paket zwischen zwei
 * Scans eine **neue** gefährliche Permission bekommen hat, typischerweise durch ein App-Update.
 *
 * Nur Pakete, die schon *vorher* eine Baseline hatten ([previousDangerousPermissions] enthält
 * einen Eintrag), zählen — ein frisch installiertes Paket hat keine Baseline zum Vergleichen und
 * ist deshalb nie ein "eskaliert"-Fund, nur ein neuer Baseline-Eintrag (der Aufrufer,
 * `de.ble1st.warden.appmanagement.SuspiciousAppScanController`, persistiert
 * [currentDangerousPermissions] nach jedem Aufruf, exakt wie bei den anderen Baseline-Signalen).
 *
 * Bewusst nur "neue Permission dazugekommen" (`current - previous` nicht leer), nicht auch
 * "Permission entfernt" — eine entfernte gefährliche Permission ist eine Verbesserung, kein
 * Verdachtssignal.
 */
object PermissionEscalationDecision {

    fun evaluate(
        previousDangerousPermissions: Map<String, Set<String>>,
        currentDangerousPermissions: Map<String, Set<String>>,
    ): Set<String> =
        currentDangerousPermissions
            .filter { (pkg, current) ->
                val previous = previousDangerousPermissions[pkg] ?: return@filter false
                (current - previous).isNotEmpty()
            }
            .keys
}
