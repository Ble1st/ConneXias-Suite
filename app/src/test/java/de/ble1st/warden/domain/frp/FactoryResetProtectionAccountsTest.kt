package de.ble1st.warden.domain.frp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FactoryResetProtectionAccountsTest {

    @Test
    fun normalizeSplitsLinesAndCommas() {
        assertEquals(
            listOf("a@example.com", "b@example.com"),
            FactoryResetProtectionAccounts.normalize(" a@example.com \n b@example.com, a@example.com "),
        )
    }

    @Test
    fun emptyIsRejected() {
        assertEquals(
            FactoryResetProtectionDecision.Empty,
            FactoryResetProtectionAccounts.evaluateRaw("  \n , "),
        )
    }

    @Test
    fun validSingleAccount() {
        val decision = FactoryResetProtectionAccounts.evaluateRaw("owner@example.com")
        assertTrue(decision is FactoryResetProtectionDecision.Valid)
        assertEquals(
            listOf("owner@example.com"),
            (decision as FactoryResetProtectionDecision.Valid).accounts,
        )
    }

    @Test
    fun tooLongIsRejected() {
        val tooLong = "a".repeat(FactoryResetProtectionAccounts.MAX_ACCOUNT_LENGTH + 1)
        assertEquals(
            FactoryResetProtectionDecision.TooLong,
            FactoryResetProtectionAccounts.evaluate(listOf(tooLong)),
        )
    }
}
