package de.ble1st.warden.domain.attestation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Prüft [AttestationExtensionParser] gegen einen von Hand gebauten DER-Record. Ein echter
 * Attestation-Record vom Gerät wäre als Testfixture ungeeignet — er ist gerätespezifisch,
 * mehrere Kilobyte groß und hängt an einer Challenge; die hier gebaute Minimalstruktur trifft
 * dagegen genau die Pfade, auf die es ankommt: hochnummerierte Kontext-Tags ([704]/[705]/[706],
 * die mehrbyte-kodiert werden), die Vorrangregel Hardware- vor Software-Liste, und das
 * Fehlerverhalten bei abgeschnittenen Daten.
 */
class AttestationExtensionParserTest {

    // --- Mini-DER-Baukasten -------------------------------------------------------------------

    private fun len(size: Int): ByteArray = when {
        size < 0x80 -> byteArrayOf(size.toByte())
        size < 0x100 -> byteArrayOf(0x81.toByte(), size.toByte())
        else -> byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + len(content.size) + content

    /** Kontextspezifisches, konstruiertes Tag mit hoher Nummer, z. B. [704] -> BF 85 40. */
    private fun contextTlv(tagNumber: Int, content: ByteArray): ByteArray {
        val tagBytes = mutableListOf<Byte>()
        var n = tagNumber
        val parts = mutableListOf<Int>()
        while (n > 0) {
            parts.add(0, n and 0x7F)
            n = n shr 7
        }
        for ((index, part) in parts.withIndex()) {
            val isLast = index == parts.size - 1
            tagBytes.add((if (isLast) part else part or 0x80).toByte())
        }
        return byteArrayOf(0xBF.toByte()) + tagBytes.toByteArray() + len(content.size) + content
    }

    private fun int(value: Int): ByteArray {
        var v = value
        val bytes = mutableListOf<Byte>()
        if (v == 0) bytes.add(0)
        while (v > 0) {
            bytes.add(0, (v and 0xFF).toByte())
            v = v shr 8
        }
        // führendes 0x00, falls das höchste Bit gesetzt wäre (DER-Vorzeichenregel)
        if (bytes.isNotEmpty() && (bytes[0].toInt() and 0x80) != 0) bytes.add(0, 0)
        return tlv(0x02, bytes.toByteArray())
    }

    private fun enumerated(value: Int): ByteArray = tlv(0x0A, byteArrayOf(value.toByte()))
    private fun bool(value: Boolean): ByteArray = tlv(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0x00))
    private fun octets(vararg bytes: Byte): ByteArray = tlv(0x04, bytes)
    private fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.reduceOrNull { a, b -> a + b } ?: ByteArray(0))

    private fun rootOfTrust(locked: Boolean, bootState: Int): ByteArray =
        contextTlv(704, sequence(octets(1, 2, 3), bool(locked), enumerated(bootState), octets(9)))

    private fun keyDescription(
        attestationSecurityLevel: Int = 1,
        hardware: ByteArray = sequence(),
        software: ByteArray = sequence(),
    ): ByteArray = sequence(
        int(4),
        enumerated(attestationSecurityLevel),
        int(4),
        enumerated(attestationSecurityLevel),
        octets(0x11, 0x22),
        octets(),
        software,
        hardware,
    )

    // --- Tests --------------------------------------------------------------------------------

    @Test
    fun `liest Verified Boot, Sperre und Patch-Stand aus der Hardware-Liste`() {
        val record = keyDescription(
            attestationSecurityLevel = 2,
            hardware = sequence(rootOfTrust(locked = true, bootState = 0), contextTlv(706, int(202608))),
        )
        val result = AttestationExtensionParser.parse(record, chainTrusted = true)

        assertEquals(VerifiedBootState.VERIFIED, result.verifiedBootState)
        assertEquals(true, result.deviceLocked)
        assertEquals(AttestationSecurityLevel.STRONG_BOX, result.securityLevel)
        assertEquals(202608, result.osPatchLevel)
        assertEquals(true, result.chainTrusted)
    }

    @Test
    fun `Verified Boot wird ausschliesslich aus der Hardware-Liste gelesen`() {
        // Die Software-Liste behauptet "verified", die Hardware-Liste sagt "unverified".
        // Maßgeblich ist die Hardware-Liste — genau der Angriff, gegen den das schützt.
        val record = keyDescription(
            software = sequence(rootOfTrust(locked = true, bootState = 0)),
            hardware = sequence(rootOfTrust(locked = false, bootState = 2)),
        )
        val result = AttestationExtensionParser.parse(record, chainTrusted = null)

        assertEquals(VerifiedBootState.UNVERIFIED, result.verifiedBootState)
        assertEquals(false, result.deviceLocked)
    }

    @Test
    fun `Patch-Stand faellt auf die Software-Liste zurueck`() {
        val record = keyDescription(
            software = sequence(contextTlv(706, int(202601))),
            hardware = sequence(rootOfTrust(locked = true, bootState = 0)),
        )
        val result = AttestationExtensionParser.parse(record, chainTrusted = null)
        assertEquals(202601, result.osPatchLevel)
    }

    @Test
    fun `unbekannter Boot-State wird UNBEKANNT, nicht VERIFIED`() {
        val record = keyDescription(hardware = sequence(rootOfTrust(locked = true, bootState = 99)))
        val result = AttestationExtensionParser.parse(record, chainTrusted = null)
        assertEquals(VerifiedBootState.UNBEKANNT, result.verifiedBootState)
    }

    @Test
    fun `abgeschnittener Record liefert UNBEKANNT statt einer Ausnahme`() {
        val record = keyDescription(hardware = sequence(rootOfTrust(locked = true, bootState = 0)))
        val truncated = record.copyOfRange(0, record.size / 2)
        val result = AttestationExtensionParser.parse(truncated, chainTrusted = false)

        assertEquals(VerifiedBootState.UNBEKANNT, result.verifiedBootState)
        assertNull(result.deviceLocked)
        assertEquals(false, result.chainTrusted)
    }

    @Test
    fun `Muell-Eingabe liefert UNBEKANNT`() {
        val result = AttestationExtensionParser.parse(byteArrayOf(0x42, 0x13, 0x37), chainTrusted = null)
        assertEquals(VerifiedBootState.UNBEKANNT, result.verifiedBootState)
    }

    @Test
    fun `leere Eingabe liefert UNBEKANNT`() {
        val result = AttestationExtensionParser.parse(ByteArray(0), chainTrusted = null)
        assertEquals(VerifiedBootState.UNBEKANNT, result.verifiedBootState)
    }
}
