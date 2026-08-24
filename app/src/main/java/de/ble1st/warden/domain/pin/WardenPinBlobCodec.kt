package de.ble1st.warden.domain.pin

import java.security.MessageDigest

/**
 * Meilenstein H.4 (Konzept Abschnitt 6: "CBOR Deterministic Encoding"). Minimaler, selbst
 * geschriebener CBOR-Kodierer/-Dekodierer statt einer externen CBOR-Bibliothek — bewusste
 * Entscheidung wie beim `[1-Byte-Nonce-Länge][Nonce][Ciphertext]`-Format von `EnvelopeFile` (B.4):
 * ein festes, kleines Schema (sechs Felder in fester Reihenfolge) rechtfertigt keine
 * allgemeine Bibliothek, und "deterministisch" ist hier **strukturell** statt per generischem
 * Kanonisierungs-Algorithmus erreicht — [WardenPinBlob] wird immer als CBOR-**Array** (nicht Map)
 * mit exakt sechs Feldern in fester Reihenfolge kodiert, jede Ganzzahl in minimaler Byte-Länge
 * (RFC 8949 "Preferred Serialization"): es gibt gar keine Map-Schlüssel-Sortierungsfrage, für die
 * gleiche logische Blob-Werte gibt es nur eine mögliche Byte-Repräsentation.
 *
 * [decode] ist bewusst **streng**: jede Diskrepanz (falscher Major-Type, falsche Array-Länge,
 * überzählige/fehlende Bytes) wirft eine Exception statt etwas zu erraten oder stillschweigend zu
 * ignorieren — Aufrufer (`SentinelStateDecision.load`) behandeln jede Exception einheitlich als
 * "Blob korrupt" → Fail-Safe-Sperre (Invariante 6), nie als "leer"/gültig maskiert.
 */
object WardenPinBlobCodec {

    private const val FIELD_COUNT = 6

    fun encode(blob: WardenPinBlob): ByteArray {
        val fields = listOf(
            encodeUInt(blob.counter),
            encodeBytes(blob.previousHash),
            encodeBool(blob.locked),
            encodeUInt(blob.failedAttempts.toLong()),
            encodeUInt(blob.backoffUntilEpochSeconds),
            encodeText(blob.pinHash),
        )
        var total = encodeArrayHeader(fields.size)
        for (field in fields) total += field
        return total
    }

    fun decode(bytes: ByteArray): WardenPinBlob {
        val cursor = Cursor(bytes)
        val count = cursor.readArrayHeader()
        require(count == FIELD_COUNT) { "Warden-Pin-Blob: erwartete $FIELD_COUNT Felder, bekam $count" }

        val counter = cursor.readUInt()
        val previousHash = cursor.readBytes()
        val locked = cursor.readBool()
        val failedAttempts = cursor.readUInt()
        val backoffUntilEpochSeconds = cursor.readUInt()
        val pinHash = cursor.readText()
        require(cursor.atEnd()) { "Warden-Pin-Blob: unerwartete überzählige Bytes am Ende" }

        return WardenPinBlob(
            counter = counter,
            previousHash = previousHash,
            locked = locked,
            failedAttempts = failedAttempts.toInt(),
            backoffUntilEpochSeconds = backoffUntilEpochSeconds,
            pinHash = pinHash,
        )
    }

    /** SHA-256 über die kanonische Kodierung — die "H(vorheriger_blob)"-Referenz für die nächste
     * Version (lokale Hash-Kette, erkennt eine zurückgespielte alte Blob-Datei). Anders als im
     * ConneXias-Framework-Quellprojekt gibt es hier keinen Cross-APK-Mirror-Vergleich mehr (s.
     * Klassendoc [WardenPinBlobCodec] bzw. Plan-Abschnitt "Presence: Sentinels PIN-Logik
     * portiert"), nur die lokale Kette selbst. */
    fun hashOf(blob: WardenPinBlob): ByteArray = MessageDigest.getInstance("SHA-256").digest(encode(blob))

    // --- Kodierung: RFC-8949-Major-Types 0 (uint), 2 (bstr), 3 (tstr), 4 (array), 7 (bool) ---

    private fun header(majorType: Int, value: Long): ByteArray {
        val mt = majorType shl 5
        return when {
            value < 24 -> byteArrayOf((mt or value.toInt()).toByte())
            value < 256 -> byteArrayOf((mt or 24).toByte(), value.toByte())
            value < 65536 -> byteArrayOf((mt or 25).toByte(), (value shr 8).toByte(), value.toByte())
            value < 4_294_967_296L -> byteArrayOf(
                (mt or 26).toByte(),
                (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
            )
            else -> byteArrayOf(
                (mt or 27).toByte(),
                (value shr 56).toByte(), (value shr 48).toByte(), (value shr 40).toByte(), (value shr 32).toByte(),
                (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
            )
        }
    }

    private fun encodeUInt(value: Long): ByteArray = header(0, value)
    private fun encodeBytes(value: ByteArray): ByteArray = header(2, value.size.toLong()) + value
    private fun encodeText(value: String): ByteArray {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        return header(3, utf8.size.toLong()) + utf8
    }
    private fun encodeBool(value: Boolean): ByteArray = byteArrayOf(if (value) 0xf5.toByte() else 0xf4.toByte())
    private fun encodeArrayHeader(count: Int): ByteArray = header(4, count.toLong())

    /** Cursor-basierter Leser — kennt nur genau das Schema, das [encode] erzeugt, kein
     * allgemeiner CBOR-Parser. */
    private class Cursor(private val bytes: ByteArray) {
        private var pos = 0

        fun atEnd(): Boolean = pos == bytes.size

        private fun readHeader(expectedMajorType: Int): Long {
            require(pos < bytes.size) { "unerwartetes Ende der Daten" }
            val b = bytes[pos].toInt() and 0xFF
            val majorType = b ushr 5
            require(majorType == expectedMajorType) {
                "erwarteter CBOR-Major-Type $expectedMajorType, bekam $majorType"
            }
            val additional = b and 0x1F
            pos++
            return when {
                additional < 24 -> additional.toLong()
                additional == 24 -> readRawUInt(1)
                additional == 25 -> readRawUInt(2)
                additional == 26 -> readRawUInt(4)
                additional == 27 -> readRawUInt(8)
                else -> throw IllegalArgumentException("nicht unterstütztes CBOR-Additional-Info $additional")
            }
        }

        private fun readRawUInt(byteCount: Int): Long {
            require(pos + byteCount <= bytes.size) { "unerwartetes Ende der Daten" }
            var value = 0L
            repeat(byteCount) {
                value = (value shl 8) or (bytes[pos].toLong() and 0xFF)
                pos++
            }
            return value
        }

        fun readArrayHeader(): Int {
            val len = readHeader(4)
            require(len in 0..Int.MAX_VALUE.toLong()) { "Array-Länge außerhalb des unterstützten Bereichs" }
            return len.toInt()
        }

        fun readUInt(): Long = readHeader(0)

        fun readBytes(): ByteArray {
            val len = readHeader(2)
            require(len in 0..(bytes.size - pos).toLong()) { "Byte-String-Länge übersteigt verbleibende Daten" }
            val result = bytes.copyOfRange(pos, pos + len.toInt())
            pos += len.toInt()
            return result
        }

        fun readText(): String {
            val len = readHeader(3)
            require(len in 0..(bytes.size - pos).toLong()) { "Text-String-Länge übersteigt verbleibende Daten" }
            val result = String(bytes, pos, len.toInt(), Charsets.UTF_8)
            pos += len.toInt()
            return result
        }

        fun readBool(): Boolean {
            require(pos < bytes.size) { "unerwartetes Ende der Daten" }
            val b = bytes[pos].toInt() and 0xFF
            pos++
            return when (b) {
                0xf4 -> false
                0xf5 -> true
                else -> throw IllegalArgumentException("erwarteter CBOR-Bool (0xf4/0xf5), bekam 0x${b.toString(16)}")
            }
        }
    }
}
