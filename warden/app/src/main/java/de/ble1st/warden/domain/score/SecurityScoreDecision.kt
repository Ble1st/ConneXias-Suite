package de.ble1st.warden.domain.score

import de.ble1st.warden.domain.attestation.AttestationFinding
import de.ble1st.warden.domain.attestation.AttestationFindingType
import de.ble1st.warden.domain.encryption.KeystoreSecurityLevel

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
 * - Kein Kategorie-Drill-down — weiterhin bewusste Scope-Reduktion (die vier Kategorie-Zeilen im
 *   Screen zeigen die Aufschlüsselung bereits, ein eigener Bildschirm pro Kategorie böte darüber
 *   hinaus keinen neuen Informationsgehalt). Der kreisförmige Gauge selbst wurde am 2026-09-03
 *   nachgereicht ([de.ble1st.warden.ui.SecurityScoreScreen]-Klassendoc) — reine Zeichenergänzung,
 *   diese Berechnungslogik blieb unverändert. Die 30-Tage-Historie selbst wurde am 2026-08-30
 *   nachgereicht
 *   ([de.ble1st.warden.score.SecurityScoreHistoryStore]), genau wie hier vorgezeichnet: als reiner
 *   Anbau, ohne dass sich an dieser Berechnungslogik etwas geändert hat — [evaluate] bleibt eine
 *   zustandslose Momentaufnahme, die Historie lebt ausschließlich im Store.
 * - **KeyStore-Hardwarebindung fließt seit 2026-09-04 zusätzlich in die Geräte-Integrität ein**
 *   (Feature 5 "Storage Encryption Verification", s.
 *   [de.ble1st.warden.domain.encryption.EncryptionRecommendationDecision]-Klassendoc für den
 *   vollständigen Realitätsabgleich) — ein rein software-basierter Schlüssel kostet dieselbe
 *   Kategorie einen zusätzlichen Abzug, `UNKNOWN` (Lesefehler dieses einen Signals) bewusst
 *   keinen: Unsicherheit wird hier nicht bestraft, dieselbe Fail-Safe-Haltung wie sonst im
 *   Projekt.
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
    private const val KEYSTORE_SOFTWARE_PENALTY = 15

    /**
     * Key-Attestation-Abzüge (2026-09-05, Tier-1 der DPC-Recherche). Ein *nachgewiesen* entsperrter
     * Bootloader wiegt schwerer als jeder Heuristik-Fund: [ROOT_INDICATOR_PENALTY] beruht auf
     * Indizien, dieser Wert auf einem hardware-signierten Record. Beide zusammen können den
     * Integritätswert auf 0 drücken — das ist beabsichtigt und beschreibt die Lage korrekt.
     */
    private const val VERIFIED_BOOT_FAILED_PENALTY = 60
    private const val BOOTLOADER_UNVERIFIED_PENALTY = 55
    private const val DEVICE_UNLOCKED_PENALTY = 20

    /**
     * Veralteter Sicherheitspatch. Das ist die Kennzahl, für die beim Bau des Security Scores die
     * Kategorie „Update-Status" gestrichen wurde („nichts in Warden kann das beantworten", s.
     * `warden/CLAUDE.md`) — der Attestation-Record beantwortet sie jetzt, deshalb fließt sie hier
     * in die Geräte-Integrität ein statt als fünfte Kategorie mit eigener Gewichtung: die
     * Gewichte der vier bestehenden Kategorien sind aufeinander abgestimmt und öffentlich
     * dokumentiert, eine fünfte hätte alle vier verschoben.
     */
    private const val PATCH_LEVEL_STALE_PENALTY = 10
    private const val PATCH_LEVEL_VERY_STALE_PENALTY = 25

    fun threatScore(warningFindings: Int, hasCriticalFinding: Boolean): Int {
        if (hasCriticalFinding) return CRITICAL_FINDING_SCORE
        return (100 - warningFindings * WARNING_FINDING_PENALTY).coerceIn(0, 100)
    }

    /** "Woran liegt es" für die Bedrohungs-Kategorie (2026-09-05, Nutzerwunsch) — dieselben
     * Rohwerte, die auch [threatScore] verwendet, nur als lesbarer Text statt als Zahl. Reine
     * Ableitung, kein zusätzlicher Zustand. */
    fun threatReasons(warningFindings: Int, hasCriticalFinding: Boolean): List<String> = buildList {
        if (hasCriticalFinding) {
            add("Mindestens ein kritischer Fund im Sicherheits-Scanner — die Kategorie wird auf 0 gesetzt, unabhängig von allem anderen.")
        }
        if (warningFindings > 0) {
            add("$warningFindings offene(r) Warnfund(e) im Sicherheits-Scanner (je -$WARNING_FINDING_PENALTY Punkte).")
        }
        if (isEmpty()) add("Keine offenen Funde im Sicherheits-Scanner.")
    }

    /** `totalApps == 0` (keine Fremd-Apps installiert, oder das Audit lief noch nie) zählt als
     * bestmöglicher Wert statt als 0/undefiniert — es gibt schlicht nichts zu beanstanden. */
    fun permissionScore(totalApps: Int, flaggedApps: Int): Int {
        if (totalApps <= 0) return 100
        val ratio = flaggedApps.toDouble() / totalApps
        return (100 - ratio * 100).toInt().coerceIn(0, 100)
    }

    fun permissionReasons(totalApps: Int, flaggedApps: Int): List<String> = buildList {
        when {
            totalApps <= 0 -> add("Keine Fremd-Apps installiert (oder das Rechte-Audit lief noch nie).")
            flaggedApps <= 0 -> add("Keine der $totalApps geprüften Apps hat übermäßig viele gefährliche Berechtigungen.")
            else -> add("$flaggedApps von $totalApps geprüften Apps haben übermäßig viele gefährliche Berechtigungen.")
        }
    }

    /** Abzüge sind additiv, nicht "schlechtestes Signal gewinnt" wie bei Bedrohungen — Root
     * *und* deaktivierte Speicherverschlüsselung zusammen sind ein deutlich schlechteres Bild als
     * jedes der beiden für sich. */
    fun integrityScore(
        rootIndicatorCount: Int,
        adbEnabled: Boolean,
        developerOptionsEnabled: Boolean,
        storageEncrypted: Boolean,
        keystoreSecurityLevel: KeystoreSecurityLevel,
        attestationFindings: List<AttestationFinding> = emptyList(),
    ): Int {
        var score = 100
        if (rootIndicatorCount > 0) score -= ROOT_INDICATOR_PENALTY
        if (adbEnabled) score -= ADB_ENABLED_PENALTY
        if (developerOptionsEnabled) score -= DEVELOPER_OPTIONS_PENALTY
        if (!storageEncrypted) score -= STORAGE_NOT_ENCRYPTED_PENALTY
        if (keystoreSecurityLevel == KeystoreSecurityLevel.SOFTWARE) score -= KEYSTORE_SOFTWARE_PENALTY
        score -= attestationPenalty(attestationFindings)
        return score.coerceIn(0, 100)
    }

    /**
     * Abzüge aus den Attestation-Befunden. Bewusst **kein** Abzug für
     * [AttestationFindingType.ATTESTATION_UNAVAILABLE], [AttestationFindingType.SELF_SIGNED_BOOT_KEY],
     * [AttestationFindingType.ATTESTATION_SOFTWARE_ONLY] und
     * [AttestationFindingType.CHAIN_UNTRUSTED]: die ersten beiden sind kein Mangel (nicht
     * unterstütztes Gerät bzw. bewusst gehärtetes Custom-ROM, s.
     * [de.ble1st.warden.domain.attestation.AttestationDecision]), die letzten beiden sagen nur, dass
     * die *Prüfung* schwach ist — nicht, dass das Gerät kompromittiert wäre. Sie erscheinen
     * trotzdem als Befund in der UI; „Unsicherheit wird nicht bestraft" gilt nur für die Punkte.
     */
    private fun attestationPenalty(findings: List<AttestationFinding>): Int {
        var penalty = 0
        for (finding in findings) {
            penalty += when (finding.type) {
                AttestationFindingType.VERIFIED_BOOT_FAILED -> VERIFIED_BOOT_FAILED_PENALTY
                AttestationFindingType.BOOTLOADER_UNVERIFIED -> BOOTLOADER_UNVERIFIED_PENALTY
                AttestationFindingType.DEVICE_UNLOCKED -> DEVICE_UNLOCKED_PENALTY
                AttestationFindingType.PATCH_LEVEL_STALE -> PATCH_LEVEL_STALE_PENALTY
                AttestationFindingType.PATCH_LEVEL_VERY_STALE -> PATCH_LEVEL_VERY_STALE_PENALTY
                AttestationFindingType.SELF_SIGNED_BOOT_KEY,
                AttestationFindingType.ATTESTATION_SOFTWARE_ONLY,
                AttestationFindingType.CHAIN_UNTRUSTED,
                AttestationFindingType.ATTESTATION_UNAVAILABLE,
                -> 0
            }
        }
        return penalty
    }

    fun integrityReasons(
        rootIndicatorCount: Int,
        adbEnabled: Boolean,
        developerOptionsEnabled: Boolean,
        storageEncrypted: Boolean,
        keystoreSecurityLevel: KeystoreSecurityLevel,
        attestationFindings: List<AttestationFinding> = emptyList(),
    ): List<String> = buildList {
        if (rootIndicatorCount > 0) add("Root-/Magisk-Indikatoren gefunden (-$ROOT_INDICATOR_PENALTY).")
        if (adbEnabled) add("ADB (USB-Debugging) aktiviert (-$ADB_ENABLED_PENALTY).")
        if (developerOptionsEnabled) add("Entwickleroptionen aktiviert (-$DEVELOPER_OPTIONS_PENALTY).")
        if (!storageEncrypted) add("Speicherverschlüsselung nicht aktiv (-$STORAGE_NOT_ENCRYPTED_PENALTY).")
        if (keystoreSecurityLevel == KeystoreSecurityLevel.SOFTWARE) {
            add("Schlüssel nur software-basiert, nicht hardwaregebunden (-$KEYSTORE_SOFTWARE_PENALTY).")
        }
        for (finding in attestationFindings) {
            when (finding.type) {
                AttestationFindingType.VERIFIED_BOOT_FAILED ->
                    add("Verified Boot fehlgeschlagen — hardware-bestätigt (-$VERIFIED_BOOT_FAILED_PENALTY).")
                AttestationFindingType.BOOTLOADER_UNVERIFIED ->
                    add("Bootloader entsperrt — hardware-bestätigt (-$BOOTLOADER_UNVERIFIED_PENALTY).")
                AttestationFindingType.DEVICE_UNLOCKED ->
                    add("Gerät als entsperrt attestiert (-$DEVICE_UNLOCKED_PENALTY).")
                AttestationFindingType.PATCH_LEVEL_STALE ->
                    add("Sicherheitspatch veraltet (-$PATCH_LEVEL_STALE_PENALTY).")
                AttestationFindingType.PATCH_LEVEL_VERY_STALE ->
                    add("Sicherheitspatch stark veraltet (-$PATCH_LEVEL_VERY_STALE_PENALTY).")
                AttestationFindingType.SELF_SIGNED_BOOT_KEY ->
                    add("Eigener Verified-Boot-Schlüssel (Custom-ROM) — kein Abzug.")
                AttestationFindingType.ATTESTATION_SOFTWARE_ONLY ->
                    add("Attestation nur software-basiert, wenig aussagekräftig — kein Abzug.")
                AttestationFindingType.CHAIN_UNTRUSTED ->
                    add("Attestation-Kette führt zu keiner bekannten Google-Wurzel — kein Abzug.")
                AttestationFindingType.ATTESTATION_UNAVAILABLE ->
                    add("Key Attestation auf diesem Gerät nicht auslesbar — kein Abzug.")
            }
        }
        if (isEmpty()) add("Keine Abzüge — Gerät wirkt strukturell unauffällig.")
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

    fun hardeningReasons(activeCount: Int, totalCount: Int): List<String> = buildList {
        if (totalCount <= 0) {
            add("Safeguard-Katalog nicht lesbar.")
        } else {
            add("$activeCount von $totalCount Safeguards im Härtungs-Katalog sind aktiv.")
        }
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
        keystoreSecurityLevel: KeystoreSecurityLevel,
        activeSafeguards: Int,
        totalSafeguards: Int,
        attestationFindings: List<AttestationFinding> = emptyList(),
    ): SecurityScoreBreakdown {
        val threat = threatScore(warningFindings, hasCriticalFinding)
        val permission = permissionScore(totalApps, flaggedApps)
        val integrity = integrityScore(
            rootIndicatorCount, adbEnabled, developerOptionsEnabled, storageEncrypted, keystoreSecurityLevel, attestationFindings,
        )
        val hardening = hardeningScore(activeSafeguards, totalSafeguards)
        val total = total(threat, permission, integrity, hardening)
        return SecurityScoreBreakdown(
            threatScore = threat,
            threatReasons = threatReasons(warningFindings, hasCriticalFinding),
            permissionScore = permission,
            permissionReasons = permissionReasons(totalApps, flaggedApps),
            integrityScore = integrity,
            integrityReasons = integrityReasons(
                rootIndicatorCount, adbEnabled, developerOptionsEnabled, storageEncrypted, keystoreSecurityLevel, attestationFindings,
            ),
            hardeningScore = hardening,
            hardeningReasons = hardeningReasons(activeSafeguards, totalSafeguards),
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

/** [threatReasons]/[permissionReasons]/[integrityReasons]/[hardeningReasons] (2026-09-05,
 * Nutzerwunsch "soll auch zeigen, woran es liegt") sind reine Text-Ableitungen aus denselben
 * Rohwerten, die die jeweilige `*Score`-Zahl bereits ergeben — kein zusätzlicher Lesepfad, keine
 * eigene Fehlerquelle. Nie leer: eine perfekte Kategorie bekommt ihre eigene "keine Abzüge"-Zeile,
 * damit "woran liegt es" auch im guten Fall eine Antwort hat, statt stillschweigend nichts zu
 * zeigen. */
data class SecurityScoreBreakdown(
    val threatScore: Int,
    val threatReasons: List<String>,
    val permissionScore: Int,
    val permissionReasons: List<String>,
    val integrityScore: Int,
    val integrityReasons: List<String>,
    val hardeningScore: Int,
    val hardeningReasons: List<String>,
    val total: Int,
    val level: SecurityScoreLevel,
)
