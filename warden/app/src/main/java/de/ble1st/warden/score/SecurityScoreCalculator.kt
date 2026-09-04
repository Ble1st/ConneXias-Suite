package de.ble1st.warden.score

import android.content.Context
import de.ble1st.warden.appmanagement.PermissionAuditScanner
import de.ble1st.warden.bus.ConcordBus
import de.ble1st.warden.domain.appmanagement.SuspiciousSignal
import de.ble1st.warden.domain.appmanagement.ThreatSeverity
import de.ble1st.warden.domain.score.SecurityScoreBreakdown
import de.ble1st.warden.domain.score.SecurityScoreDecision

/**
 * Android-Andockstelle für [SecurityScoreDecision] (Feature 5 "Security Score Dashboard",
 * 2026-08-29) — sammelt die vier Kategorien ausschließlich aus bereits bestehenden Lesepfaden,
 * kein neuer Scan-Mechanismus:
 * - Bedrohungen: [ConcordBus.listSuspiciousAppFindings] (derselbe Aufruf wie im Sicherheits-
 *   Scanner-Bildschirm).
 * - Rechte-Hygiene: [PermissionAuditScanner] (derselbe Aufruf wie im Permission-Audit-Bildschirm).
 * - Geräte-Integrität: [ConcordBus.deviceIntegrityStatus].
 * - Härtungsgrad: [ConcordBus.listSafeguards]/[ConcordBus.safeguardStates] (derselbe gebündelte
 *   Aufruf wie im Safeguards-Bildschirm, s. dessen Klassendoc zu Befund Q-2).
 *
 * **Wirft weiter, statt einen Fehler in einen "sicheren" Platzhalterwert umzudeuten** — dieselbe
 * "Fail-Safe über bequem"-Konvention wie überall sonst im Projekt (s. CLAUDE.md). Alle vier
 * Lesepfade laufen ohnehin durch denselben [ConcordBus.authorize]/DPM-Zugriff; schlägt einer fehl
 * (z. B. kein Device Owner mehr aktiv), ist kein Teilergebnis vertrauenswürdiger als ein anderes.
 * Der Aufrufer (`WardenStatusActivity`) fängt über `runCatching`/`getOrNull()` ab, exakt wie beim
 * Permission-Audit-Scan, und zeigt einen Fehlerzustand statt eines geschätzten Werts.
 *
 * Läuft komplett auf `Dispatchers.IO` (Aufrufer-Pflicht) — [PermissionAuditScanner] allein kann
 * mehrere hundert `PackageManager`-Aufrufe auslösen.
 */
class SecurityScoreCalculator(private val context: Context, private val concordBus: ConcordBus) {

    fun calculate(): SecurityScoreBreakdown {
        val severities = concordBus.listSuspiciousAppFindings()
            .map { ThreatSeverity.highest(SuspiciousSignal.fromBitmask(it.signalsBitmask)) }

        val permissionAudit = PermissionAuditScanner(context).scan()
        val integrity = concordBus.deviceIntegrityStatus()

        val safeguardIds = concordBus.listSafeguards()
        val safeguardStates = concordBus.safeguardStates(safeguardIds)

        return SecurityScoreDecision.evaluate(
            warningFindings = severities.count { it == ThreatSeverity.WARNING },
            hasCriticalFinding = severities.any { it == ThreatSeverity.CRITICAL },
            totalApps = permissionAudit.size,
            flaggedApps = permissionAudit.count { it.tooManyDangerousPermissions },
            rootIndicatorCount = integrity.rootIndicators.size,
            adbEnabled = integrity.adbEnabled,
            developerOptionsEnabled = integrity.developerOptionsEnabled,
            storageEncrypted = integrity.storageEncrypted,
            keystoreSecurityLevel = integrity.keystoreSecurityLevel,
            activeSafeguards = safeguardStates.values.count { it == true },
            totalSafeguards = safeguardIds.size,
        )
    }
}
