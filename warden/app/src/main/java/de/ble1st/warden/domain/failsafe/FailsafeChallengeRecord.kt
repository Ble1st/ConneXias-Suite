package de.ble1st.warden.domain.failsafe

/**
 * Versioned on-disk challenge: random bytes plus issue timestamp so [FailsafeChallengeTtl]
 * can expire a stolen signed response. Legacy raw 32-byte blobs (no header) decode as
 * `issuedAtEpochMs = 0` and are therefore immediately expired.
 */
data class FailsafeChallengeRecord(
    val challenge: ByteArray,
    val issuedAtEpochMs: Long,
) {
    init {
        require(challenge.isNotEmpty()) { "failsafe challenge must not be empty" }
        require(issuedAtEpochMs >= 0) { "issuedAt must not be negative" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FailsafeChallengeRecord) return false
        return issuedAtEpochMs == other.issuedAtEpochMs && challenge.contentEquals(other.challenge)
    }

    override fun hashCode(): Int = 31 * challenge.contentHashCode() + issuedAtEpochMs.hashCode()
}

object FailsafeChallengeRecordCodec {
    private const val VERSION: Byte = 1
    private const val HEADER_SIZE = 1 + 8

    fun encode(record: FailsafeChallengeRecord): ByteArray {
        val out = ByteArray(HEADER_SIZE + record.challenge.size)
        out[0] = VERSION
        var value = record.issuedAtEpochMs
        for (i in 8 downTo 1) {
            out[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        record.challenge.copyInto(out, destinationOffset = HEADER_SIZE)
        return out
    }

    fun decode(bytes: ByteArray): FailsafeChallengeRecord {
        if (bytes.isEmpty()) {
            throw IllegalArgumentException("empty failsafe challenge record")
        }
        // Pre-TTL storage wrote the raw 32-byte challenge with no header.
        if (bytes.size == 32) {
            return FailsafeChallengeRecord(challenge = bytes, issuedAtEpochMs = 0L)
        }
        require(bytes[0] == VERSION) { "unknown failsafe challenge record version ${bytes[0]}" }
        require(bytes.size > HEADER_SIZE) { "failsafe challenge record too short" }
        var issuedAt = 0L
        for (i in 1..8) {
            issuedAt = (issuedAt shl 8) or (bytes[i].toLong() and 0xFF)
        }
        return FailsafeChallengeRecord(
            challenge = bytes.copyOfRange(HEADER_SIZE, bytes.size),
            issuedAtEpochMs = issuedAt,
        )
    }
}
