package de.ble1st.warden.domain.pin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Meilenstein H.3 — reine JVM-Unit-Tests, [WardenPinDecision] ist framework-frei (kein
 * Keystore/keine Rust-Engine nötig, `verify` wird als Fake-Lambda übergeben).
 */
class WardenPinDecisionTest {

    private val pin = "1234".toByteArray()
    private val hash = "\$argon2id\$fake-hash"

    @Test
    fun missingHashIsReportedAsNotConfigured() {
        val result = WardenPinDecision.evaluate(
            storedHash = null,
            enteredPin = pin,
            verify = { _, _ -> true },
        )

        assertEquals(WardenPinDecisionResult.NotConfigured, result)
    }

    @Test
    fun matchingPinIsAccepted() {
        val result = WardenPinDecision.evaluate(
            storedHash = hash,
            enteredPin = pin,
            verify = { _, _ -> true },
        )

        assertEquals(WardenPinDecisionResult.Accepted, result)
    }

    @Test
    fun nonMatchingPinIsRejected() {
        val result = WardenPinDecision.evaluate(
            storedHash = hash,
            enteredPin = pin,
            verify = { _, _ -> false },
        )

        assertEquals(WardenPinDecisionResult.Rejected, result)
    }

    @Test
    fun verifyReceivesExactPinAndHash() {
        var receivedPin: ByteArray? = null
        var receivedHash: String? = null

        WardenPinDecision.evaluate(
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

        WardenPinDecision.evaluate(
            storedHash = null,
            enteredPin = pin,
            verify = { _, _ -> verifyCalled = true; true },
        )

        assertFalse("fehlender Hash darf verify() nicht aufrufen", verifyCalled)
    }
}
