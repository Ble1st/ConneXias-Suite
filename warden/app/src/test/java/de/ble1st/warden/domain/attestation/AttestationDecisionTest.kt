package de.ble1st.warden.domain.attestation

import de.ble1st.warden.domain.appmanagement.ThreatSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prüft die Bewertungsregeln aus [AttestationDecision] — insbesondere die beiden Stellen, an
 * denen bewusst *nicht* das naheliegende Ergebnis herauskommt: Custom-ROM und Unlesbarkeit. */
class AttestationDecisionTest {

    private fun attestation(
        bootState: VerifiedBootState = VerifiedBootState.VERIFIED,
        deviceLocked: Boolean? = true,
        securityLevel: AttestationSecurityLevel = AttestationSecurityLevel.TRUSTED_ENVIRONMENT,
        patchLevel: Int? = 202609,
        chainTrusted: Boolean? = true,
    ) = DeviceAttestation(bootState, deviceLocked, securityLevel, patchLevel, 160000, chainTrusted)

    @Test
    fun `sauberes Geraet erzeugt keinen Befund`() {
        val findings = AttestationDecision.evaluate(attestation(), nowYearMonth = 202609)
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `entsperrter Bootloader ist kritisch`() {
        val findings = AttestationDecision.evaluate(
            attestation(bootState = VerifiedBootState.UNVERIFIED, deviceLocked = false),
            nowYearMonth = 202609,
        )
        val types = findings.map { it.type }
        assertTrue(AttestationFindingType.BOOTLOADER_UNVERIFIED in types)
        assertEquals(ThreatSeverity.CRITICAL, AttestationDecision.highestSeverity(findings))
    }

    @Test
    fun `DEVICE_UNLOCKED wird nicht zusaetzlich zu UNVERIFIED gemeldet`() {
        // Beide Felder sagen dasselbe; zwei Befunde für einen Sachverhalt wären doppelte Abzüge.
        val findings = AttestationDecision.evaluate(
            attestation(bootState = VerifiedBootState.UNVERIFIED, deviceLocked = false),
            nowYearMonth = 202609,
        )
        assertTrue(findings.none { it.type == AttestationFindingType.DEVICE_UNLOCKED })
    }

    @Test
    fun `entsperrtes Geraet bei sonst intakter Kette wird eigenstaendig gemeldet`() {
        val findings = AttestationDecision.evaluate(
            attestation(bootState = VerifiedBootState.VERIFIED, deviceLocked = false),
            nowYearMonth = 202609,
        )
        assertTrue(findings.any { it.type == AttestationFindingType.DEVICE_UNLOCKED })
    }

    @Test
    fun `Custom-ROM mit eigenem Schluessel ist nur INFO`() {
        val findings = AttestationDecision.evaluate(
            attestation(bootState = VerifiedBootState.SELF_SIGNED),
            nowYearMonth = 202609,
        )
        assertEquals(ThreatSeverity.INFO, AttestationDecision.highestSeverity(findings))
    }

    @Test
    fun `nicht auslesbare Attestation ist nur INFO`() {
        val findings = AttestationDecision.evaluate(
            DeviceAttestation.UNBEKANNT,
            nowYearMonth = 202609,
        )
        assertEquals(ThreatSeverity.INFO, AttestationDecision.highestSeverity(findings))
        assertTrue(findings.any { it.type == AttestationFindingType.ATTESTATION_UNAVAILABLE })
    }

    @Test
    fun `Patch-Stand knapp unter der Grenze erzeugt keinen Befund`() {
        val findings = AttestationDecision.evaluate(
            attestation(patchLevel = 202607), // 2 Monate alt, Grenze liegt bei 3
            nowYearMonth = 202609,
        )
        assertTrue(findings.none { it.type == AttestationFindingType.PATCH_LEVEL_STALE })
    }

    @Test
    fun `Patch-Stand ab drei Monaten ist WARNING, ab sechs CRITICAL`() {
        val stale = AttestationDecision.evaluate(attestation(patchLevel = 202606), nowYearMonth = 202609)
        assertTrue(stale.any { it.type == AttestationFindingType.PATCH_LEVEL_STALE })

        val veryStale = AttestationDecision.evaluate(attestation(patchLevel = 202603), nowYearMonth = 202609)
        assertTrue(veryStale.any { it.type == AttestationFindingType.PATCH_LEVEL_VERY_STALE })
        assertEquals(ThreatSeverity.CRITICAL, AttestationDecision.highestSeverity(veryStale))
    }

    @Test
    fun `Patch-Stand in der Zukunft erzeugt keinen Befund`() {
        // Kommt bei OEM-Vorab-Bulletins real vor und ist kein Mangel.
        val findings = AttestationDecision.evaluate(attestation(patchLevel = 202612), nowYearMonth = 202609)
        assertTrue(findings.none { it.type.name.startsWith("PATCH_LEVEL") })
    }

    @Test
    fun `Jahreswechsel wird korrekt gerechnet`() {
        assertEquals(4, AttestationDecision.monthsBetween(202511, 202603))
    }

    @Test
    fun `unplausibler Patch-Stand liefert null statt Muell`() {
        assertNull(AttestationDecision.monthsBetween(202613, 202609)) // Monat 13
        assertNull(AttestationDecision.monthsBetween(199901, 202609)) // vor Androids Existenz
        assertNull(AttestationDecision.monthsBetween(null, 202609))
    }
}
