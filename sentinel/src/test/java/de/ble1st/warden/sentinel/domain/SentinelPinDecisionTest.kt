package de.ble1st.warden.sentinel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 1:1-Port-Test von `de.ble1st.warden.domain.pin.WardenPinDecisionTest` — reine JVM-Unit-Tests,
 * [SentinelPinDecision] ist framework-frei (kein Keystore/keine Rust-Engine nötig, `verify` wird
 * als Fake-Lambda übergeben).
 */
class SentinelPinDecisionTest {

    private val pin = "1234".toByteArray()
    private val hash = "\$argon2id\$fake-hash"

    @Test
    fun missingHashIsReportedAsNotConfigured() {
        val result = SentinelPinDecision.evaluate(
            storedHash = null,
            enteredPin = pin,
            verify = { _, _ -> true },
        )

        assertEquals(SentinelPinDecisionResult.NotConfigured, result)
    }

    @Test
    fun matchingPinIsAccepted() {
        val result = SentinelPinDecision.evaluate(
            storedHash = hash,
            enteredPin = pin,
            verify = { _, _ -> true },
        )

        assertEquals(SentinelPinDecisionResult.Accepted, result)
    }

    @Test
    fun nonMatchingPinIsRejected() {
        val result = SentinelPinDecision.evaluate(
            storedHash = hash,
            enteredPin = pin,
            verify = { _, _ -> false },
        )

        assertEquals(SentinelPinDecisionResult.Rejected, result)
    }

    @Test
    fun verifyReceivesExactPinAndHash() {
        var receivedPin: ByteArray? = null
        var receivedHash: String? = null

        SentinelPinDecision.evaluate(
            storedHash = hash,
            enteredPin = pin,
            verify = { p, h ->
                receivedPin = p
                receivedHash = h
                true
            },
        )

        assertEquals(pin, receivedPin)
        assertEquals(hash, receivedHash)
    }

    @Test
    fun verifyIsNeverCalledWithoutAConfiguredHash() {
        var verifyCalled = false

        SentinelPinDecision.evaluate(
            storedHash = null,
            enteredPin = pin,
            verify = { _, _ -> verifyCalled = true; true },
        )

        assertFalse("fehlender Hash darf verify() nicht aufrufen", verifyCalled)
    }
}
