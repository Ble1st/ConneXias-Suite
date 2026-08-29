package de.ble1st.warden.domain.score

/**
 * Feature 5 "Security Score Dashboard" aus `docs/umsetzungsplan-7-features.md`, umgesetzt
 * 2026-08-29. Reine Berechnungslogik, kein Android-Import (s. CLAUDE.md "Decision/Executor-
 * Trennung") — [de.ble1st.warden.score.SecurityScoreCalculator] sammelt die Rohwerte aus bereits
 * bestehenden Lesepfaden und ruft [evaluate] auf.
 *
 * **Abweichungen vom ursprünglichen Plan** (derselbe Realitätsabgleich wie bei den übrigen
 * Features aus diesem Plandokument, s. `warden-cellsecurity-feature-2026-08-29`-Memo):
 * - Der Plan sah fünf Kategorien vor, darunter "Update-Status" — Warden hat keinen Update-Server
 *   und keine Play-Store-Anbindung, es gibt lokal nichts, das diese Kategorie füllen könnte.
 *   Ersetzt durch vier Kategorien, die ausschließlich aus bereits vorhandenen, lokal messbaren
 *   Signalen berechnet werden: Bedrohungen (Verdachtsscanner), Rechte-Hygiene (Permission-Audit),
 *   Geräte-Integrität ([de.ble1st.warden.integrity.DeviceIntegrityStatus]) und Härtungsgrad
 *   (Anteil aktiver Safeguards aus dem reversiblen Katalog).
 * - Kein kreisförmiger Gauge, keine 30-Tage-Historie, kein Kategorie-Drill-down — bewusste
 *   Scope-Reduktion; jede Berechnung ist eine Momentaufnahme, keine über die Zeit gespeicherte
 *   Reihe. Bei Bedarf später ergänzbar, ohne dass sich an dieser Berechnungslogik etwas ändert.
 *
 * **Gewichtung** (muss in Summe 1.0 ergeben, s. [SecurityScoreDecisionTest.weightsSumToOne]):
 * Bedrohungen zählen am stärksten, weil ein einzelner kritischer Fund einen akuten, laufenden
 * Angriff/eine bereits kompromittierte App bedeutet — die anderen drei Kategorien beschreiben eher
 * strukturelle Grundhärtung als eine akute Situation.
 */
object SecurityScoreDecision {

    const val THREAT_WEIGHT = 0.35
    const val PERMISSION_WEIGHT = 0.25
    const val INTEGRITY_WEIGHT = 0.20
    const val HARDENING_WEIGHT = 0.20

    /** Punktabzug pro offenem WARNING-Fund. Ein einzelner CRITICAL-Fund zieht die ganze Kategorie
     * sofort auf 0 — dieselbe "schlechtestes Signal gewinnt"-Haltung wie [de.ble1st.warden.domain
     * .appmanagement.ThreatSeverity], ein kritischer Fund (frisch aktivierter Geräteadmin,
     * Signaturwechsel) relativiert sich nicht durch die Abwesenheit anderer Funde. */
    private const val WARNING_FINDING_PENALTY = 20
    private const val CRITICAL_FINDING_SCORE = 0

    private const val ROOT_INDICATOR_PENALTY = 50
    private const val ADB_ENABLED_PENALTY = 15
    private const val DEVELOPER_OPTIONS_PENALTY = 10
    private const val STORAGE_NOT_ENCRYPTED_PENALTY = 25

    fun threatScore(warningFindings: Int, hasCriticalFinding: Boolean): Int {
        if (hasCriticalFinding) return CRITICAL_FINDING_SCORE
        return (100 - warningFindings * WARNING_FINDING_PENALTY).coerceIn(0, 100)
    }

    /** `totalApps == 0` (keine Fremd-Apps installiert, oder das Audit lief noch nie) zählt als
     * bestmöglicher Wert statt als 0/undefiniert — es gibt schlicht nichts zu beanstanden. */
    fun permissionScore(totalApps: Int, flaggedApps: Int): Int {
        if (totalApps <= 0) return 100
        val ratio = flaggedApps.toDouble() / totalApps
        return (100 - ratio * 100).toInt().coerceIn(0, 100)
    }

    /** Abzüge sind additiv, nicht "schlechtestes Signal gewinnt" wie bei Bedrohungen — Root
     * *und* deaktivierte Speicherverschlüsselung zusammen sind ein deutlich schlechteres Bild als
     * jedes der beiden für sich. */
    fun integrityScore(
        rootIndicatorCount: Int,
        adbEnabled: Boolean,
        developerOptionsEnabled: Boolean,
        storageEncrypted: Boolean,
    ): Int {
        var score = 100
        if (rootIndicatorCount > 0) score -= ROOT_INDICATOR_PENALTY
        if (adbEnabled) score -= ADB_ENABLED_PENALTY
        if (developerOptionsEnabled) score -= DEVELOPER_OPTIONS_PENALTY
        if (!storageEncrypted) score -= STORAGE_NOT_ENCRYPTED_PENALTY
        return score.coerceIn(0, 100)
    }

    /** Anteil der 32 reversiblen Katalog-Safeguards, die aktuell aktiv sind — ein Näherungswert
     * für den strukturellen Härtungsgrad, **kein** Werturteil über jeden einzelnen Schalter (z. B.
     * ist das Deaktivieren der Kamera für viele Nutzer schlicht unpraktikabel im Alltag; s.
     * `WardenProfile`-Klassendoc zur bewussten Alltag/Reise/Maximal-Abstufung). `totalCount == 0`
     * kann in der Praxis nur bei einem Lesefehler vorkommen ([SafeguardCatalog.reversible] ist nie
     * leer) — 0 statt 100, weil ein leerer Katalog eher ein Warnsignal als ein gutes Zeichen ist. */
    fun hardeningScore(activeCount: Int, totalCount: Int): Int {
        if (totalCount <= 0) return 0
        return ((activeCount.toDouble() / totalCount) * 100).toInt().coerceIn(0, 100)
    }

    fun total(threat: Int, permission: Int, integrity: Int, hardening: Int): Int =
        (threat * THREAT_WEIGHT + permission * PERMISSION_WEIGHT + integrity * INTEGRITY_WEIGHT + hardening * HARDENING_WEIGHT)
            .toInt()
            .coerceIn(0, 100)

    fun levelFor(total: Int): SecurityScoreLevel = when {
        total >= 85 -> SecurityScoreLevel.SEHR_GUT
        total >= 65 -> SecurityScoreLevel.GUT
        total >= 40 -> SecurityScoreLevel.VERBESSERUNGSWUERDIG
        else -> SecurityScoreLevel.KRITISCH
    }

    fun evaluate(
        warningFindings: Int,
        hasCriticalFinding: Boolean,
        totalApps: Int,
        flaggedApps: Int,
        rootIndicatorCount: Int,
        adbEnabled: Boolean,
        developerOptionsEnabled: Boolean,
        storageEncrypted: Boolean,
        activeSafeguards: Int,
        totalSafeguards: Int,
    ): SecurityScoreBreakdown {
        val threat = threatScore(warningFindings, hasCriticalFinding)
        val permission = permissionScore(totalApps, flaggedApps)
        val integrity = integrityScore(rootIndicatorCount, adbEnabled, developerOptionsEnabled, storageEncrypted)
        val hardening = hardeningScore(activeSafeguards, totalSafeguards)
        val total = total(threat, permission, integrity, hardening)
        return SecurityScoreBreakdown(
            threatScore = threat,
            permissionScore = permission,
            integrityScore = integrity,
            hardeningScore = hardening,
            total = total,
            level = levelFor(total),
        )
    }
}

enum class SecurityScoreLevel(val label: String) {
    SEHR_GUT("Sehr gut"),
    GUT("Gut"),
    VERBESSERUNGSWUERDIG("Verbesserungswürdig"),
    KRITISCH("Kritisch"),
}

data class SecurityScoreBreakdown(
    val threatScore: Int,
    val permissionScore: Int,
    val integrityScore: Int,
    val hardeningScore: Int,
    val total: Int,
    val level: SecurityScoreLevel,
)
