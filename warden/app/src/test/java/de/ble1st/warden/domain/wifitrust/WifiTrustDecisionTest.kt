package de.ble1st.warden.domain.wifitrust

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiTrustDecisionTest {

    @Test
    fun noCurrentSsidIsNotConnected() {
        assertEquals(WifiTrustDecision.Outcome.NotConnected, WifiTrustDecision.evaluate(null, setOf("Heimnetz")))
    }

    @Test
    fun ssidInTrustedSetIsTrusted() {
        assertEquals(
            WifiTrustDecision.Outcome.Trusted("Heimnetz"),
            WifiTrustDecision.evaluate("Heimnetz", setOf("Heimnetz", "Büro")),
        )
    }

    @Test
    fun ssidNotInTrustedSetIsUntrusted() {
        assertEquals(
            WifiTrustDecision.Outcome.Untrusted("Fremdnetz"),
            WifiTrustDecision.evaluate("Fremdnetz", setOf("Heimnetz", "Büro")),
        )
    }

    @Test
    fun emptyTrustedSetMakesEveryNetworkUntrusted() {
        assertEquals(WifiTrustDecision.Outcome.Untrusted("Heimnetz"), WifiTrustDecision.evaluate("Heimnetz", emptySet()))
    }
}
