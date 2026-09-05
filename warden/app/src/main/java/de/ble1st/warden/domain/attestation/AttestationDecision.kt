package de.ble1st.warden.domain.attestation

import de.ble1st.warden.domain.appmanagement.ThreatSeverity

/** Die einzelnen Befunde, die [AttestationDecision] melden kann — als Typ statt als fertiger Text,
 * damit die Textauswahl in der UI-Schicht bleibt (`strings.xml`, s. Projektkonvention: die
 * `domain`-Schicht importiert nichts aus Android). */
enum class AttestationFindingType {
    /** Bootloader entsperrt, kein Verified Boot. Der klassische Root-/Manipulationsfall. */
    BOOTLOADER_UNVERIFIED,

    /** Verified Boot lief und ist fehlgeschlagen — schwerwiegender als [BOOTLOADER_UNVERIFIED]:
     * hier hat die Prüfung nicht gefehlt, sondern *nicht bestanden*. */
    VERIFIED_BOOT_FAILED,

    /** Eigener Verified-Boot-Schlüssel (Custom-ROM mit gesperrtem Bootloader, z. B. GrapheneOS).
     * Bewusst nur [ThreatSeverity.INFO] — s. Klassendoc von [AttestationDecision]. */
    SELF_SIGNED_BOOT_KEY,

    /** `RootOfTrust.deviceLocked == false`, unabhängig vom Boot-State. */
    DEVICE_UNLOCKED,

    /** Sicherheitspatch älter als [AttestationDecision.PATCH_LEVEL_WARN_MONTHS] Monate. */
    PATCH_LEVEL_STALE,

    /** Sicherheitspatch älter als [AttestationDecision.PATCH_LEVEL_CRITICAL_MONTHS] Monate. */
    PATCH_LEVEL_VERY_STALE,

    /** Der Schlüssel liegt nur in Software — die Attestation selbst ist damit wertlos als
     * Hardware-Nachweis. */
    ATTESTATION_SOFTWARE_ONLY,

    /** Die Zertifikatskette führt nicht zu einer bekannten Google-Attestation-Wurzel. */
    CHAIN_UNTRUSTED,

    /** Attestation auf diesem Gerät gar nicht auslesbar. Rein informativ. */
    ATTESTATION_UNAVAILABLE,
}

/** Ein einzelner Befund, gleiche Form wie
 * [de.ble1st.warden.domain.encryption.EncryptionRecommendation]. */
data class AttestationFinding(
    val type: AttestationFindingType,
    val severity: ThreatSeverity,
)

/**
 * Bewertet eine [DeviceAttestation] (2026-09-05, Tier-1-Vorschlag aus der DPC-Recherche).
 * Framework-frei und damit als JVM-Unit-Test prüfbar.
 *
 * **Warum `SELF_SIGNED` nur `INFO` ist:** ein eigener Verified-Boot-Schlüssel bei *gesperrtem*
 * Bootloader ist der Normalzustand eines bewusst gehärteten Custom-ROMs (GrapheneOS ist Wardens
 * erklärtes Vorbild für Duress-PIN und USB-Port-Lock, s. `warden/CLAUDE.md`). Das als Verstoß zu
 * werten hieße, ausgerechnet das sicherste realistische Setup schlechter zu bewerten als ein
 * Stock-ROM. Gemeldet wird es trotzdem — der Gerätebesitzer soll sehen, dass der Anker nicht der
 * des OEM ist, denn wenn er das *nicht* selbst eingerichtet hat, ist es ein Alarmzeichen.
 *
 * **Unsicherheit wird nicht bestraft:** [VerifiedBootState.UNBEKANNT] und ein fehlender
 * Patch-Level erzeugen höchstens einen `INFO`-Befund und ziehen im Score nichts ab — dieselbe
 * Haltung wie bei [de.ble1st.warden.domain.encryption.KeystoreSecurityLevel.UNKNOWN] und
 * ausdrücklich anders als bei einem *gelesenen* schlechten Wert.
 */
object AttestationDecision {

    /** Ab hier gilt der Patch-Stand als veraltet (`WARNING`). Drei Monate, weil Googles
     * Android-Security-Bulletin monatlich erscheint und ein bis zwei ausgelassene Zyklen bei OEM-
     * Geräten alltäglich sind — erst darüber wird es zum Befund. */
    const val PATCH_LEVEL_WARN_MONTHS: Int = 3

    /** Ab hier `CRITICAL`: über ein halbes Jahr ohne Sicherheitspatch heißt in der Praxis, dass
     * öffentlich dokumentierte, exploitbare Lücken offenstehen. */
    const val PATCH_LEVEL_CRITICAL_MONTHS: Int = 6

    /**
     * @param attestation das ausgelesene Ergebnis.
     * @param nowYearMonth aktueller Monat als `YYYYMM` — hereingereicht statt hier aus einer Uhr
     *   gelesen, damit die Alterungsgrenzen testbar bleiben (dieselbe Injektion wie bei
     *   [de.ble1st.warden.domain.score.ScoreReminderDecision]).
     */
    fun evaluate(attestation: DeviceAttestation, nowYearMonth: Int): List<AttestationFinding> {
        val findings = mutableListOf<AttestationFinding>()

        when (attestation.verifiedBootState) {
            VerifiedBootState.FAILED ->
                findings += AttestationFinding(AttestationFindingType.VERIFIED_BOOT_FAILED, ThreatSeverity.CRITICAL)
            VerifiedBootState.UNVERIFIED ->
                findings += AttestationFinding(AttestationFindingType.BOOTLOADER_UNVERIFIED, ThreatSeverity.CRITICAL)
            VerifiedBootState.SELF_SIGNED ->
                findings += AttestationFinding(AttestationFindingType.SELF_SIGNED_BOOT_KEY, ThreatSeverity.INFO)
            VerifiedBootState.UNBEKANNT ->
                findings += AttestationFinding(AttestationFindingType.ATTESTATION_UNAVAILABLE, ThreatSeverity.INFO)
            VerifiedBootState.VERIFIED -> Unit
        }

        // Eigener Befund neben dem Boot-State: die beiden Felder sind unabhängig, und ein
        // entsperrtes Gerät mit sonst intakter Kette wäre sonst unsichtbar.
        if (attestation.deviceLocked == false && attestation.verifiedBootState != VerifiedBootState.UNVERIFIED) {
            findings += AttestationFinding(AttestationFindingType.DEVICE_UNLOCKED, ThreatSeverity.WARNING)
        }

        when (monthsBetween(attestation.osPatchLevel, nowYearMonth)) {
            null -> Unit
            in PATCH_LEVEL_CRITICAL_MONTHS..Int.MAX_VALUE ->
                findings += AttestationFinding(AttestationFindingType.PATCH_LEVEL_VERY_STALE, ThreatSeverity.CRITICAL)
            in PATCH_LEVEL_WARN_MONTHS until PATCH_LEVEL_CRITICAL_MONTHS ->
                findings += AttestationFinding(AttestationFindingType.PATCH_LEVEL_STALE, ThreatSeverity.WARNING)
            else -> Unit
        }

        if (attestation.securityLevel == AttestationSecurityLevel.SOFTWARE) {
            findings += AttestationFinding(AttestationFindingType.ATTESTATION_SOFTWARE_ONLY, ThreatSeverity.WARNING)
        }

        if (attestation.chainTrusted == false) {
            findings += AttestationFinding(AttestationFindingType.CHAIN_UNTRUSTED, ThreatSeverity.WARNING)
        }

        return findings
    }

    /**
     * Monate zwischen einem `YYYYMM`-Patch-Level und dem aktuellen `YYYYMM`. `null`, wenn kein
     * Patch-Level vorliegt oder der Wert unplausibel ist (kein gültiger Monat, offensichtlich vor
     * Androids Existenz) — dann gibt es schlicht keine Aussage, statt einer aus Müll gerechneten.
     * Ein Patch-Level **in der Zukunft** ergibt einen negativen Wert und damit keinen Befund; das
     * kommt bei OEM-Vorab-Bulletins tatsächlich vor und ist kein Problem.
     */
    fun monthsBetween(patchLevelYearMonth: Int?, nowYearMonth: Int): Int? {
        val patch = patchLevelYearMonth ?: return null
        val patchYear = patch / 100
        val patchMonth = patch % 100
        if (patchMonth !in 1..12 || patchYear < 2008) return null
        val nowYear = nowYearMonth / 100
        val nowMonth = nowYearMonth % 100
        if (nowMonth !in 1..12) return null
        return (nowYear - patchYear) * 12 + (nowMonth - patchMonth)
    }

    /** Höchster Schweregrad über alle Befunde, `null` bei keinem Befund — dieselbe
     * "worst-signal-wins"-Zusammenfassung wie bei [ThreatSeverity.highest]. */
    fun highestSeverity(findings: List<AttestationFinding>): ThreatSeverity? =
        findings.maxByOrNull { it.severity.ordinal }?.severity
}
