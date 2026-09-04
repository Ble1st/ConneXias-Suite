package de.ble1st.warden.domain.failsafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Meilenstein D.3 — reine JVM-Unit-Tests, [FailsafeDecision] ist framework-frei (kein Keystore/
 * keine Rust-Engine nötig, `verify` wird als Fake-Lambda übergeben).
 */
class FailsafeDecisionTest {

    private val challenge = byteArrayOf(1, 2, 3)
    private val publicKey = byteArrayOf(9, 8, 7)

    @Test
    fun missingKeyIsReportedBeforeMissingChallenge() {
        val result = FailsafeDecision.evaluate(
            pendingChallenge = null,
            trustedPublicKey = null,
            verify = { _, _ -> true },
        )

        assertEquals(FailsafeDecisionResult.NoKeyConfigured, result)
    }

    @Test
    fun missingChallengeIsReportedWhenKeyConfigured() {
        val result = FailsafeDecision.evaluate(
            pendingChallenge = null,
            trustedPublicKey = publicKey,
            verify = { _, _ -> true },
        )

        assertEquals(FailsafeDecisionResult.NoChallengePending, result)
    }

    @Test
    fun validResponseIsAccepted() {
        val result = FailsafeDecision.evaluate(
            pendingChallenge = challenge,
            trustedPublicKey = publicKey,
            verify = { _, _ -> true },
        )

        assertEquals(FailsafeDecisionResult.Accepted, result)
    }

    @Test
    fun invalidResponseIsRejected() {
        val result = FailsafeDecision.evaluate(
            pendingChallenge = challenge,
            trustedPublicKey = publicKey,
            verify = { _, _ -> false },
        )

        assertEquals(FailsafeDecisionResult.Rejected, result)
    }

    @Test
    fun verifyReceivesExactChallengeAndPublicKey() {
        var receivedChallenge: ByteArray? = null
        var receivedPublicKey: ByteArray? = null

        FailsafeDecision.evaluate(
            pendingChallenge = challenge,
            trustedPublicKey = publicKey,
            verify = { c, k ->
                receivedChallenge = c
                receivedPublicKey = k
                true
            },
        )

        assertEquals(challenge, receivedChallenge)
        assertEquals(publicKey, receivedPublicKey)
    }

    @Test
    fun verifyIsNeverCalledWithoutBothPreconditions() {
        var verifyCalled = false
        val verify: (ByteArray, ByteArray) -> Boolean = { _, _ -> verifyCalled = true; true }

        FailsafeDecision.evaluate(pendingChallenge = null, trustedPublicKey = null, verify = verify)
        assertFalse(verifyCalled)

        FailsafeDecision.evaluate(pendingChallenge = null, trustedPublicKey = publicKey, verify = verify)
        assertFalse("fehlende Challenge darf verify() nicht aufrufen", verifyCalled)

        FailsafeDecision.evaluate(pendingChallenge = challenge, trustedPublicKey = null, verify = verify)
        assertFalse("fehlender Schlüssel darf verify() nicht aufrufen", verifyCalled)
    }
}
