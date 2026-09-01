package de.ble1st.warden.pin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ble1st.warden.crypto.Engine
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import de.ble1st.warden.domain.pin.WardenPinBlob
import de.ble1st.warden.domain.pin.WardenPinBlobCodec
import de.ble1st.warden.domain.pin.WardenPinDecision
import de.ble1st.warden.domain.pin.WardenPinDecisionResult
import de.ble1st.warden.domain.pin.WardenPinStateDecision
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.connexias_engine.PasswordHash

/**
 * Meilenstein H.4/H.5/H.6/H.7 — deckt den vollen Rundlauf ab, den [WardenPinActivity] zur
 * Laufzeit durchläuft: Blob schreiben/lesen über [WardenPinStore] ([EnvelopeFile]/[KeystoreKek],
 * Device-Protected-Storage), Zähler-/Ketten-Fortschritt bei jeder Mutation, Argon2id-PIN-Setup/
 * -Verify über [Engine], sowie Fail-Safe-Erkennung eines manipulierten Blobs über
 * [WardenPinStateDecision].
 *
 * Kein `recoverFromTrustedBase`-Äquivalent mehr (Cross-APK-Zähler-/Hash-Spiegel-Wiederherstellung
 * entfällt, s. [WardenPinStore]-Klassendoc) — ein korrupter Blob wird stattdessen über den
 * ohnehin vorhandenen Offline-Failsafe behandelt.
 */
@RunWith(AndroidJUnit4::class)
class WardenPinStoreInstrumentedTest {

    private lateinit var testDir: File
    private lateinit var kek: KeystoreKek
    private lateinit var anchorKek: KeystoreKek
    private val purpose = "test-warden-pin-${UUID.randomUUID()}"
    private val anchorPurpose = "$purpose-anchor"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
            .createDeviceProtectedStorageContext()
        testDir = File(context.cacheDir, "warden-pin-test-${UUID.randomUUID()}").apply { mkdirs() }
        kek = KeystoreKek.forPurpose(context, purpose)
        anchorKek = KeystoreKek.forPurpose(context, anchorPurpose)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        runCatching { keyStore.deleteEntry("warden.kek.$purpose") }
        runCatching { keyStore.deleteEntry("warden.kek.$anchorPurpose") }
    }

    private fun newStore() = WardenPinStore(
        EnvelopeFile(
            dataFile = File(testDir, "warden_pin.envelope"),
            wrappedDekFile = File(testDir, "warden_pin.dek"),
            wrapper = kek,
            context = "warden:pin:v1".toByteArray(),
        ),
        EnvelopeFile(
            dataFile = File(testDir, "warden_pin.anchor.envelope"),
            wrappedDekFile = File(testDir, "warden_pin.anchor.dek"),
            wrapper = anchorKek,
            context = "warden:pin-replay:v1".toByteArray(),
        ),
    )

    @Test
    fun doesNotExistBeforeFirstWrite() {
        assertFalse(newStore().exists())
    }

    @Test
    fun firstPersistedVersionAdvancesFromGenesis() {
        val store = newStore()

        val first = store.persistNewVersion { current -> current.copy(pinHash = "hash-1") }

        assertEquals(1L, first.counter)
        assertArrayEquals(WardenPinBlobCodec.hashOf(WardenPinBlob.genesis()), first.previousHash)
        assertEquals("hash-1", first.pinHash)
    }

    @Test
    fun loadRoundTripsThroughRealEnvelopeAndDeviceProtectedStorage() {
        val store = newStore()
        val written = store.persistNewVersion { current -> current.copy(pinHash = "hash-1", locked = true, failedAttempts = 2) }

        assertEquals(written, store.load())
    }

    @Test
    fun successiveVersionsFormARealHashChain() {
        val store = newStore()

        val v1 = store.persistNewVersion { it.copy(pinHash = "hash-1") }
        val v2 = store.persistNewVersion { it.copy(failedAttempts = 1) }
        val v3 = store.persistNewVersion { it.copy(locked = false) }

        assertEquals(1L, v1.counter)
        assertEquals(2L, v2.counter)
        assertEquals(3L, v3.counter)
        assertArrayEquals(WardenPinBlobCodec.hashOf(v1), v2.previousHash)
        assertArrayEquals(WardenPinBlobCodec.hashOf(v2), v3.previousHash)
    }

    @Test
    fun mutateCannotOverrideCounterOrPreviousHash() {
        val store = newStore()
        val v1 = store.persistNewVersion { it.copy(pinHash = "hash-1") }

        // Ein fehlerhafter mutate-Lambda versucht, counter/previousHash selbst zu setzen —
        // persistNewVersion muss das unbedingt überschreiben (WardenPinStore-Doc).
        val tampered = store.persistNewVersion { current ->
            current.copy(counter = 999L, previousHash = ByteArray(32) { 0x42 })
        }

        assertEquals(2L, tampered.counter)
        assertArrayEquals(WardenPinBlobCodec.hashOf(v1), tampered.previousHash)
    }

    @Test
    fun fullArgon2idSetupAndVerifyRoundTripAcceptsTheCorrectPin() {
        val store = newStore()
        val pin = "135790".toByteArray()
        val hash = Engine.hashPassword(pin)

        val blob = store.persistNewVersion { it.copy(pinHash = hash.phc, locked = false) }

        val result = WardenPinDecision.evaluate(
            storedHash = blob.pinHash,
            enteredPin = pin,
            verify = { entered, phc -> Engine.verifyPassword(entered, PasswordHash(phc)) },
        )
        assertEquals(WardenPinDecisionResult.Accepted, result)
    }

    @Test
    fun fullArgon2idSetupAndVerifyRoundTripRejectsAWrongPin() {
        val store = newStore()
        val blob = store.persistNewVersion { it.copy(pinHash = Engine.hashPassword("135790".toByteArray()).phc) }

        val result = WardenPinDecision.evaluate(
            storedHash = blob.pinHash,
            enteredPin = "000000".toByteArray(),
            verify = { entered, phc -> Engine.verifyPassword(entered, PasswordHash(phc)) },
        )
        assertEquals(WardenPinDecisionResult.Rejected, result)
    }

    @Test
    fun tamperedEnvelopeIsReportedAsCorruptedNotAsMissing() {
        val store = newStore()
        store.persistNewVersion { it.copy(pinHash = "hash-1") }
        val dataFile = File(testDir, "warden_pin.envelope")
        val original = dataFile.readBytes()
        // Ein Byte mitten im Ciphertext kippen — AEAD-Auth-Tag-Prüfung muss das erkennen.
        original[original.size / 2] = (original[original.size / 2].toInt() xor 0xFF).toByte()
        dataFile.writeBytes(original)

        val result = WardenPinStateDecision.load(store.exists()) { store.load() }

        assertTrue(result is WardenPinStateDecision.LoadResult.Corrupted)
    }

    @Test
    fun restoringOlderBlobAgainstCurrentAnchorIsCorrupted() {
        val store = newStore()
        store.persistNewVersion { it.copy(pinHash = "hash-1") }
        val blobFile = File(testDir, "warden_pin.envelope")
        val firstVersion = blobFile.readBytes()
        store.persistNewVersion { it.copy(pinHash = "hash-2") }
        blobFile.writeBytes(firstVersion)

        val result = WardenPinStateDecision.load(store.exists()) { store.load() }

        assertTrue(result is WardenPinStateDecision.LoadResult.Corrupted)
    }
}
