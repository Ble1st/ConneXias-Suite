package de.ble1st.warden.domain.pin

/**
 * Fixed-width encoding of the last accepted PIN blob identity: 8-byte big-endian
 * counter + 32-byte SHA-256 of that blob. Separate from [WardenPinBlobCodec] so the
 * replay slot cannot be confused with a full blob restore.
 */
object WardenPinReplayAnchorCodec {
    const val SIZE = 8 + 32

    fun encode(counter: Long, hash: ByteArray): ByteArray {
        require(counter >= 0) { "replay-anchor counter must not be negative" }
        require(hash.size == 32) { "replay-anchor hash must be 32 bytes" }
        val out = ByteArray(SIZE)
        var value = counter
        for (i in 7 downTo 0) {
            out[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        hash.copyInto(out, destinationOffset = 8)
        return out
    }

    fun decode(bytes: ByteArray): Pair<Long, ByteArray> {
        require(bytes.size == SIZE) { "replay-anchor size ${bytes.size}, expected $SIZE" }
        var counter = 0L
        for (i in 0 until 8) {
            counter = (counter shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return counter to bytes.copyOfRange(8, SIZE)
    }
}
