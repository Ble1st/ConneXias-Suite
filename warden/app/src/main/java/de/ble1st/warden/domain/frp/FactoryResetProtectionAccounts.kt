package de.ble1st.warden.domain.frp

/**
 * Unlock-account list for enterprise Factory Reset Protection. An empty list must never be
 * applied — AOSP treats that as "no account can provision", i.e. a brick after untrusted wipe.
 */
object FactoryResetProtectionAccounts {
    const val MAX_ACCOUNTS = 5
    const val MAX_ACCOUNT_LENGTH = 128

    fun normalize(raw: String): List<String> =
        raw.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_ACCOUNTS)

    fun evaluate(accounts: List<String>): FactoryResetProtectionDecision {
        if (accounts.isEmpty()) return FactoryResetProtectionDecision.Empty
        if (accounts.size > MAX_ACCOUNTS) return FactoryResetProtectionDecision.TooMany
        if (accounts.any { it.length > MAX_ACCOUNT_LENGTH }) return FactoryResetProtectionDecision.TooLong
        return FactoryResetProtectionDecision.Valid(accounts)
    }

    fun evaluateRaw(raw: String): FactoryResetProtectionDecision = evaluate(normalize(raw))
}

sealed class FactoryResetProtectionDecision {
    data class Valid(val accounts: List<String>) : FactoryResetProtectionDecision()
    data object Empty : FactoryResetProtectionDecision()
    data object TooMany : FactoryResetProtectionDecision()
    data object TooLong : FactoryResetProtectionDecision()
}
