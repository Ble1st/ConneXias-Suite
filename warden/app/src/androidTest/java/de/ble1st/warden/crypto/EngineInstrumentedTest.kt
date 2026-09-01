package de.ble1st.warden.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Meilenstein B.2 — Abnahmekriterium laut Konzept 19: "Engine aus Kotlin aufrufbar
 * (Round-Trip-Test seal/open)". Instrumented statt JVM-Unit-Test, weil die generierten
 * UniFFI-Bindings die native `.so` aus `src/main/jniLibs/` laden — das geht nur auf einem
 * echten Gerät/Emulator, nicht im JVM-Unit-Test-Stub.
 */
@RunWith(AndroidJUnit4::class)
class EngineInstrumentedTest {

    private fun testKey() = ByteArray(32) { 0x42 }

    @Test
    fun sealOpenRoundTripThroughFfi() {
        val key = testKey()
        val context = "warden:registry:v1".toByteArray()
        val plaintext = "Safeguard-Registry-Sollzustand".toByteArray()

        val envelope = Engine.seal(key, context, plaintext)
        val recovered = Engine.open(key, context, envelope)

        assertArrayEquals(plaintext, recovered)
    }

    @Test
    fun openFailsWithWrongKey() {
        val key = testKey()
        val wrongKey = testKey().also { it[0] = it[0].inc() }
        val context = "ctx".toByteArray()

        val envelope = Engine.seal(key, context, "geheim".toByteArray())

        try {
            Engine.open(wrongKey, context, envelope)
            throw AssertionError("open() hätte mit falschem Schlüssel fehlschlagen müssen")
        } catch (expected: uniffi.connexias_engine.OpenException) {
            // erwartet — Fail-Safe (Invariante 6): falscher Schlüssel darf nie "leer" liefern.
        }
    }

    @Test
    fun deriveKeyThroughFfiIsDeterministic() {
        val ikm = "eingangsmaterial".toByteArray()
        val salt = "salt".toByteArray()
        val info = "warden:registry:kek".toByteArray()

        val a = Engine.deriveKey(ikm, salt, info, 32u)
        val b = Engine.deriveKey(ikm, salt, info, 32u)

        assertArrayEquals(a, b)
    }

    @Test
    fun hashAndVerifyPasswordThroughFfi() {
        val password = "korrektes-pferd-batterie-heftklammer".toByteArray()
        val hash = Engine.hashPassword(password)

        assertTrue(hash.phc.startsWith("\$argon2id\$"))
        assertTrue(Engine.verifyPassword(password, hash))
        assertFalse(Engine.verifyPassword("falsches-passwort".toByteArray(), hash))
    }

    @Test
    fun sealProducesDistinctNoncesThroughFfi() {
        val key = testKey()
        val a = Engine.seal(key, "ctx".toByteArray(), "gleicher Klartext".toByteArray())
        val b = Engine.seal(key, "ctx".toByteArray(), "gleicher Klartext".toByteArray())

        assertNotEquals(a.nonce.toList(), b.nonce.toList())
    }
}
