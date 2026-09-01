package de.ble1st.warden.domain.failsafe

/**
 * A signed failsafe response stays valid against the stored challenge until a new
 * challenge is issued. Without a TTL, a stolen already-signed response works indefinitely.
 *
 * Clock rollback (`now < issuedAt`) is treated as expired — fail-closed rather than
 * extending the window.
 */
object FailsafeChallengeTtl {
    const val DEFAULT_TTL_MS: Long = 60L * 60L * 1000L

    fun isExpired(
        issuedAtEpochMs: Long,
        nowEpochMs: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): Boolean {
        if (issuedAtEpochMs <= 0L) return true
        return nowEpochMs < issuedAtEpochMs || nowEpochMs - issuedAtEpochMs > ttlMs
    }
}
