package de.ble1st.warden.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.connexias_engine.WrapException

/**
 * Meilenstein B.3 — Abnahme: KEK-Erzeugung im AndroidKeyStore (TEE/StrongBox-aware) und
 * DEK-Wrap/Unwrap über den [uniffi.connexias_engine.KeyWrapper]-Callback aus Kotlin heraus, real
 * durch die FFI-Grenze (Rust ruft [KeystoreKek] synchron als Callback auf). Instrumented statt
 * JVM-Unit-Test — `AndroidKeyStore` gibt es nur auf einem echten Gerät/Emulator, nicht im
 * JVM-Unit-Test-Stub.
 *
 * Jeder Test nutzt einen frischen, zufälligen Alias (statt eines festen), damit Testläufe sich
 * nicht gegenseitig über einen zwischen App-Starts persistenten Keystore-Eintrag beeinflussen;
 * [tearDown] räumt den jeweiligen Eintrag danach auf.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreKekInstrumentedTest {

    private fun freshKek(): KeystoreKek {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return KeystoreKek.forPurpose(context, "test-${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        // Best-effort-Aufräumen aller in diesem Testlauf angelegten Keystore-Aliase, damit sie
        // nicht über Testläufe hinweg akkumulieren. Kein hartes Require — ein fehlgeschlagenes
        // Löschen darf den Test nicht als fehlgeschlagen markieren (Fail-Safe gilt für
        // Produktionscode, nicht für Test-Aufräumlogik).
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.aliases().toList()
            .filter { it.startsWith("warden.kek.test-") }
            .forEach { runCatching { keyStore.deleteEntry(it) } }
    }

    @Test
    fun wrapUnwrapRoundTripThroughKeystore() {
        val kek = freshKek()
        val dek = Engine.generateDek(32u)

        val generated = Engine.generateAndWrapDek(kek, 32u)
        val recovered = Engine.unwrapDek(kek, generated.wrapped)

        assertArrayEquals(generated.dek, recovered)
        // generateAndWrapDek erzeugt selbst einen DEK — nicht denselben wie der separat oben
        // gezogene `dek`, nur zur Kontrolle, dass generateDek() unabhängig zufällig ist.
        assertNotEquals(dek.toList(), generated.dek.toList())
    }

    @Test
    fun sameKeystoreAliasUnwrapsAcrossInstances() {
        // Zwei getrennte KeystoreKek-Objekte mit demselben Alias müssen denselben, im
        // AndroidKeyStore persistierten KEK verwenden (nicht pro Objektinstanz neu erzeugen) —
        // sonst könnte ein neu instanziierter Wrapper (z. B. nach Prozess-Neustart) eigene
        // Envelopes nicht mehr entwrappen.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "test-shared-${UUID.randomUUID()}"
        val kekA = KeystoreKek.forPurpose(context, alias)
        val kekB = KeystoreKek.forPurpose(context, alias)

        val generated = Engine.generateAndWrapDek(kekA, 32u)
        val recovered = Engine.unwrapDek(kekB, generated.wrapped)

        assertArrayEquals(generated.dek, recovered)

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        runCatching { keyStore.deleteEntry("warden.kek.$alias") }
    }

    @Test
    fun unwrapFailsOnTamperedCiphertext() {
        val kek = freshKek()
        val generated = Engine.generateAndWrapDek(kek, 32u)
        val tampered = generated.wrapped.copy(
            ciphertext = generated.wrapped.ciphertext.copyOf().also {
                it[it.size - 1] = (it[it.size - 1] + 1).toByte()
            },
        )

        try {
            Engine.unwrapDek(kek, tampered)
            throw AssertionError("unwrapDek() hätte bei manipuliertem Ciphertext fehlschlagen müssen")
        } catch (expected: WrapException) {
            // erwartet — Fail-Safe (Invariante 6): manipulierter Wrap darf nie einen DEK liefern.
        }
    }
}
