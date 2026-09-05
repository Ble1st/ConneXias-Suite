package de.ble1st.warden.domain.attestation

/**
 * Minimaler DER-Leser für die Android-Key-Attestation-Erweiterung (OID `1.3.6.1.4.1.11129.2.1.17`)
 * aus dem Leaf-Zertifikat der Attestation-Kette. Framework-frei (reine `ByteArray`-Verarbeitung),
 * damit die Struktur-Logik als JVM-Unit-Test prüfbar ist — dieselbe Decision/Executor-Trennung wie
 * überall sonst: [de.ble1st.warden.integrity.KeyAttestationReader] besorgt die Bytes vom
 * `KeyStore`, hier wird nur geparst.
 *
 * **Warum ein eigener Parser statt einer ASN.1-Bibliothek (2026-09-05):** BouncyCastle o. ä. wäre
 * eine mehrere Megabyte große Abhängigkeit für exakt vier gesuchte Felder, und Warden hält seine
 * Abhängigkeitsliste bewusst kurz (s. `app/build.gradle.kts`). Gelesen wird nur, was gebraucht
 * wird — der Rest jeder `SEQUENCE` wird übersprungen, nicht interpretiert.
 *
 * Struktur laut AOSP (`KeyDescription`), nur die hier relevanten Felder benannt:
 * ```
 * KeyDescription ::= SEQUENCE {
 *   attestationVersion       INTEGER,
 *   attestationSecurityLevel ENUMERATED,   <- gelesen
 *   keyMintVersion           INTEGER,
 *   keyMintSecurityLevel     ENUMERATED,
 *   attestationChallenge     OCTET STRING,
 *   uniqueId                 OCTET STRING,
 *   softwareEnforced         AuthorizationList,
 *   hardwareEnforced         AuthorizationList }  <- gelesen
 *
 * AuthorizationList ::= SEQUENCE {
 *   ... , rootOfTrust [704] EXPLICIT RootOfTrust OPTIONAL,
 *         osVersion    [705] EXPLICIT INTEGER OPTIONAL,
 *         osPatchLevel [706] EXPLICIT INTEGER OPTIONAL, ... }
 *
 * RootOfTrust ::= SEQUENCE {
 *   verifiedBootKey   OCTET STRING,
 *   deviceLocked      BOOLEAN,             <- gelesen
 *   verifiedBootState ENUMERATED,          <- gelesen
 *   verifiedBootHash  OCTET STRING OPTIONAL }
 * ```
 *
 * **`hardwareEnforced` schlägt `softwareEnforced`, und nur ersteres zählt für Verified Boot.**
 * Werte in der Software-Liste sind von der (potenziell kompromittierten) Android-Seite gesetzt und
 * damit genau das, wogegen diese Prüfung schützen soll — `rootOfTrust` wird deshalb ausschließlich
 * aus der Hardware-Liste gelesen. Für [DeviceAttestation.osPatchLevel]/[DeviceAttestation.osVersion]
 * wird die Hardware-Liste bevorzugt und nur ersatzweise die Software-Liste herangezogen: manche
 * Keymaster-Implementierungen legen diese beiden dort ab, und ein *ungefährer* Patch-Stand ist
 * immer noch besser als gar keiner (er trägt keine Vertrauensentscheidung, nur eine Empfehlung).
 *
 * Jeder Strukturfehler führt zu `null`/[VerifiedBootState.UNBEKANNT], nie zu einer Ausnahme nach
 * außen und nie zu einem geratenen "sieht gut aus" — die Fail-Safe-Regel des Projekts, hier in
 * der Ausprägung "Unlesbarkeit ist kein Verstoß, aber auch kein Bestehen".
 */
object AttestationExtensionParser {

    /** OID der Attestation-Erweiterung im Leaf-Zertifikat. */
    const val ATTESTATION_EXTENSION_OID: String = "1.3.6.1.4.1.11129.2.1.17"

    private const val TAG_INTEGER = 0x02
    private const val TAG_BIT_STRING = 0x03
    private const val TAG_OCTET_STRING = 0x04
    private const val TAG_ENUMERATED = 0x0A
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_SET = 0x31
    private const val TAG_BOOLEAN = 0x01

    private const val CONTEXT_ROOT_OF_TRUST = 704
    private const val CONTEXT_OS_VERSION = 705
    private const val CONTEXT_OS_PATCH_LEVEL = 706

    /**
     * Parst den Inhalt der Erweiterung (den bereits ausgepackten `OCTET STRING`-Inhalt, also die
     * `KeyDescription`-`SEQUENCE` selbst).
     *
     * @param chainTrusted wird unverändert in [DeviceAttestation.chainTrusted] durchgereicht — die
     *   Kettenprüfung ist Zertifikats-, nicht Byte-Arbeit und gehört deshalb nicht hierher.
     */
    fun parse(keyDescription: ByteArray, chainTrusted: Boolean?): DeviceAttestation {
        val fields = runCatching { readKeyDescription(keyDescription) }.getOrNull()
            ?: return DeviceAttestation.UNBEKANNT.copy(chainTrusted = chainTrusted)
        return fields.copy(chainTrusted = chainTrusted)
    }

    private fun readKeyDescription(bytes: ByteArray): DeviceAttestation {
        val reader = DerReader(bytes)
        val outer = reader.readTlv() ?: return DeviceAttestation.UNBEKANNT
        if (outer.tag != TAG_SEQUENCE) return DeviceAttestation.UNBEKANNT

        val seq = DerReader(outer.value)
        seq.readTlv() // attestationVersion
        val attestationSecurityLevel = seq.readTlv()
            ?.takeIf { it.tag == TAG_ENUMERATED }
            ?.let { AttestationSecurityLevel.fromAsn1(readIntValue(it.value)) }
            ?: AttestationSecurityLevel.UNBEKANNT
        seq.readTlv() // keyMintVersion
        seq.readTlv() // keyMintSecurityLevel
        seq.readTlv() // attestationChallenge
        seq.readTlv() // uniqueId
        val softwareEnforced = seq.readTlv()
        val hardwareEnforced = seq.readTlv()

        val hardware = hardwareEnforced?.let { readAuthorizationList(it.value) } ?: AuthorizationFields()
        val software = softwareEnforced?.let { readAuthorizationList(it.value) } ?: AuthorizationFields()

        return DeviceAttestation(
            // Bewusst nur aus der Hardware-Liste, s. Klassendoc.
            verifiedBootState = hardware.verifiedBootState,
            deviceLocked = hardware.deviceLocked,
            securityLevel = attestationSecurityLevel,
            osPatchLevel = hardware.osPatchLevel ?: software.osPatchLevel,
            osVersion = hardware.osVersion ?: software.osVersion,
            chainTrusted = null,
        )
    }

    private data class AuthorizationFields(
        val verifiedBootState: VerifiedBootState = VerifiedBootState.UNBEKANNT,
        val deviceLocked: Boolean? = null,
        val osVersion: Int? = null,
        val osPatchLevel: Int? = null,
    )

    private fun readAuthorizationList(bytes: ByteArray): AuthorizationFields {
        var result = AuthorizationFields()
        val reader = DerReader(bytes)
        while (true) {
            val entry = reader.readTlv() ?: break
            when (entry.contextTagNumber) {
                CONTEXT_ROOT_OF_TRUST -> {
                    val rot = readRootOfTrust(entry.value)
                    if (rot != null) {
                        result = result.copy(verifiedBootState = rot.first, deviceLocked = rot.second)
                    }
                }
                CONTEXT_OS_VERSION -> result = result.copy(osVersion = readExplicitInt(entry.value))
                CONTEXT_OS_PATCH_LEVEL -> result = result.copy(osPatchLevel = readExplicitInt(entry.value))
                else -> Unit // jeder andere Tag ist für diese Auswertung irrelevant
            }
        }
        return result
    }

    /** `[704] EXPLICIT` umschließt eine `RootOfTrust`-`SEQUENCE`. */
    private fun readRootOfTrust(explicitContent: ByteArray): Pair<VerifiedBootState, Boolean?>? {
        val inner = DerReader(explicitContent).readTlv() ?: return null
        if (inner.tag != TAG_SEQUENCE) return null
        val seq = DerReader(inner.value)
        seq.readTlv() // verifiedBootKey
        val locked = seq.readTlv()?.takeIf { it.tag == TAG_BOOLEAN }?.let { it.value.isNotEmpty() && it.value[0] != 0.toByte() }
        val state = seq.readTlv()?.takeIf { it.tag == TAG_ENUMERATED }
            ?.let { VerifiedBootState.fromAsn1(readIntValue(it.value)) }
            ?: VerifiedBootState.UNBEKANNT
        return state to locked
    }

    /** `[705]`/`[706] EXPLICIT` umschließen je ein `INTEGER`. */
    private fun readExplicitInt(explicitContent: ByteArray): Int? {
        val inner = DerReader(explicitContent).readTlv() ?: return null
        if (inner.tag != TAG_INTEGER) return null
        return readIntValue(inner.value)
    }

    /** DER-`INTEGER`/`ENUMERATED` sind big-endian und vorzeichenbehaftet; alle hier gelesenen
     * Werte (Aufzählungen, `YYYYMM`, `AABBCC`) sind klein und nicht-negativ, deshalb genügt diese
     * bewusst einfache Umrechnung ohne `BigInteger`. */
    private fun readIntValue(bytes: ByteArray): Int {
        var value = 0
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 0xFF)
        }
        return value
    }

    /** Ein einzelnes DER-Tag-Length-Value-Element. [contextTagNumber] ist die Tag-Nummer, falls es
     * sich um ein kontextspezifisches Tag handelt (Klasse `10`), sonst `-1`. */
    private data class Tlv(val tag: Int, val contextTagNumber: Int, val value: ByteArray)

    /** Schrittweiser DER-Leser über einen Byte-Puffer. Liefert `null`, sobald der Puffer erschöpft
     * ist **oder** eine Längenangabe über das Pufferende hinauszeigt — ein abgeschnittener oder
     * manipulierter Record beendet damit die Auswertung, statt eine Ausnahme zu werfen. */
    private class DerReader(private val bytes: ByteArray) {
        private var offset = 0

        fun readTlv(): Tlv? {
            if (offset >= bytes.size) return null
            val first = bytes[offset].toInt() and 0xFF
            offset++
            val isContextSpecific = (first and 0xC0) == 0x80
            var tagNumber = first and 0x1F
            if (tagNumber == 0x1F) {
                // Hochnummeriges Tag (z. B. [704]): base-128, Fortsetzungsbit im höchsten Bit.
                tagNumber = 0
                while (true) {
                    if (offset >= bytes.size) return null
                    val b = bytes[offset].toInt() and 0xFF
                    offset++
                    tagNumber = (tagNumber shl 7) or (b and 0x7F)
                    if ((b and 0x80) == 0) break
                }
            }
            if (offset >= bytes.size) return null
            var length = bytes[offset].toInt() and 0xFF
            offset++
            if (length and 0x80 != 0) {
                val lengthBytes = length and 0x7F
                if (lengthBytes == 0 || lengthBytes > 4) return null // unbestimmte/absurde Länge
                if (offset + lengthBytes > bytes.size) return null
                length = 0
                repeat(lengthBytes) {
                    length = (length shl 8) or (bytes[offset].toInt() and 0xFF)
                    offset++
                }
            }
            if (length < 0 || offset + length > bytes.size) return null
            val value = bytes.copyOfRange(offset, offset + length)
            offset += length
            return Tlv(
                tag = first,
                contextTagNumber = if (isContextSpecific) tagNumber else -1,
                value = value,
            )
        }
    }
}
