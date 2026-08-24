package de.ble1st.warden.domain.logging

/**
 * Fixed-width encoding of the last-observed chain-tail identity for
 * [de.ble1st.warden.logging.HashChainLogStore]'s wipe guard: 8-byte big-endian sequence number +
 * 32-byte SHA-256 hash of that entry. Same byte layout as
 * [de.ble1st.warden.domain.pin.WardenPinReplayAnchorCodec] (both are "last known
 * counter/sequence + hash" anchors) — kept as its own type rather than reused, the same way
 * `EnvelopeFile`'s many callers each get their own small codec: a coincidence of layout between
 * two unrelated anchor mechanisms is not a reason to couple them.
 */
object HashChainAnchorCodec {
    const val SIZE = 8 + 32

    fun encode(sequence: Long, hash: ByteArray): ByteArray {
        require(sequence >= 0) { "wipe-guard anchor sequence must not be negative" }
        require(hash.size == 32) { "wipe-guard anchor hash must be 32 bytes" }
        val out = ByteArray(SIZE)
        var value = sequence
        for (i in 7 downTo 0) {
            out[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        hash.copyInto(out, destinationOffset = 8)
        return out
    }

    fun decode(bytes: ByteArray): Pair<Long, ByteArray> {
        require(bytes.size == SIZE) { "wipe-guard anchor size ${bytes.size}, expected $SIZE" }
        var sequence = 0L
        for (i in 0 until 8) {
            sequence = (sequence shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return sequence to bytes.copyOfRange(8, SIZE)
    }
}
