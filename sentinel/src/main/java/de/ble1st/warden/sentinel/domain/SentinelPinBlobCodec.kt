package de.ble1st.warden.sentinel.domain

/**
 * Minimaler, selbst geschriebener Kodierer für [SentinelPinBlob] — dieselbe
 * "[1 Byte Feld-Anzahl][UInt/Text-Felder in fester Reihenfolge]"-Idee wie Wardens
 * `WardenPinBlobCodec`, aber ohne dessen CBOR-Major-Type-Overhead: nur drei Felder, kein
 * `ByteArray`-Feld, kein Grund für das allgemeinere Schema.
 *
 * **Bewusst keine Hash-Kette/kein Replay-Anchor** (anders als Wardens `WardenPinBlob`, das
 * `counter`/`previousHash` trägt): Wardens Kette allein erkennt eine zurückgespielte alte
 * Blob-Datei nur *zusammen* mit einem zweiten, unabhängigen Anker (`WardenPinReplayAnchorCodec`)
 * — ohne den (und ohne den im ConneXias-Framework-Quellprojekt vorgesehenen Cross-APK-Warden-
 * Mirror-Vergleich, der hier bewusst nicht gebaut wird, s. Plan-Abschnitt "Explizit außerhalb
 * dieses Plans") wäre eine Kette in Sentinels eigenem Blob allein wirkungslose Komplexität: eine
 * zurückgespielte ältere Kopie ist in sich selbst genauso kettenkonsistent wie die aktuelle.
 * Der praktische Schaden eines zurückgespielten alten Sentinel-Blobs bleibt ohnehin begrenzt
 * (Anti-Hammering-Zähler zurückgesetzt) — kein PIN-Wert wird dadurch offengelegt, keine
 * Warden-seitige Autorität umgangen.
 *
 * [decode] ist bewusst streng (jede Diskrepanz wirft), [SentinelPinStateDecision.load]
 * behandelt jede Exception einheitlich als "Blob korrupt" — Fail-Safe, dieselbe Haltung wie im
 * Rest des Projekts.
 */
object SentinelPinBlobCodec {

    private const val FIELD_COUNT = 3

    fun encode(blob: SentinelPinBlob): ByteArray {
        val pinHashBytes = blob.pinHash.toByteArray(Charsets.UTF_8)
        require(pinHashBytes.size <= 0xFFFF) { "pinHash-Kodierung zu lang" }
        val out = ArrayList<Byte>(pinHashBytes.size + 32)
        out.add(FIELD_COUNT.toByte())
        appendUInt(out, blob.failedAttempts.toLong())
        appendUInt(out, blob.backoffUntilEpochSeconds)
        appendUShort(out, pinHashBytes.size)
        out.addAll(pinHashBytes.toList())
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): SentinelPinBlob {
        val cursor = Cursor(bytes)
        val count = cursor.readByte()
        require(count == FIELD_COUNT) { "Sentinel-Pin-Blob: erwartete $FIELD_COUNT Felder, bekam $count" }
        val failedAttempts = cursor.readUInt()
        val backoffUntilEpochSeconds = cursor.readUInt()
        val pinHashLength = cursor.readUShort()
        val pinHash = cursor.readText(pinHashLength)
        require(cursor.atEnd()) { "Sentinel-Pin-Blob: unerwartete überzählige Bytes am Ende" }
        return SentinelPinBlob(
            pinHash = pinHash,
            failedAttempts = failedAttempts.toInt(),
            backoffUntilEpochSeconds = backoffUntilEpochSeconds,
        )
    }

    private fun appendUInt(out: MutableList<Byte>, value: Long) {
        require(value in 0..0xFFFFFFFFL) { "Wert außerhalb des unterstützten 32-Bit-Bereichs: $value" }
        out.add((value ushr 24).toByte())
        out.add((value ushr 16).toByte())
        out.add((value ushr 8).toByte())
        out.add(value.toByte())
    }

    private fun appendUShort(out: MutableList<Byte>, value: Int) {
        out.add((value ushr 8).toByte())
        out.add(value.toByte())
    }

    private class Cursor(private val bytes: ByteArray) {
        private var pos = 0

        fun atEnd(): Boolean = pos == bytes.size

        fun readByte(): Int {
            require(pos < bytes.size) { "unerwartetes Ende der Daten" }
            return (bytes[pos++].toInt() and 0xFF)
        }

        fun readUInt(): Long {
            require(pos + 4 <= bytes.size) { "unerwartetes Ende der Daten" }
            var value = 0L
            repeat(4) { value = (value shl 8) or (bytes[pos++].toLong() and 0xFF) }
            return value
        }

        fun readUShort(): Int {
            require(pos + 2 <= bytes.size) { "unerwartetes Ende der Daten" }
            val value = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            pos += 2
            return value
        }

        fun readText(length: Int): String {
            require(pos + length <= bytes.size) { "Text-Länge übersteigt verbleibende Daten" }
            val result = String(bytes, pos, length, Charsets.UTF_8)
            pos += length
            return result
        }
    }
}
