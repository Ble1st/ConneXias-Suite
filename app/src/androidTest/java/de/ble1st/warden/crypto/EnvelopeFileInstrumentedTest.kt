package de.ble1st.warden.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.connexias_engine.OpenException

/**
 * Meilenstein B.4 — Abnahme (Konzept 19): "Envelope-Schema end-to-end (verschlüsselt
 * schreiben/lesen einer Testdatei)". Deckt die volle Kette Keystore-KEK (B.3) → DEK-Wrap →
 * `seal`/`open` (B.1/B.2) → Datei ab, real durch die FFI-Grenze. Instrumented statt
 * JVM-Unit-Test aus demselben Grund wie [EngineInstrumentedTest]/[KeystoreKekInstrumentedTest]
 * (AndroidKeyStore, native `.so`).
 */
@RunWith(AndroidJUnit4::class)
class EnvelopeFileInstrumentedTest {

    private lateinit var testDir: File
    private lateinit var kek: KeystoreKek
    private val purpose = "test-envelope-${UUID.randomUUID()}"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testDir = File(context.cacheDir, "envelope-file-test-${UUID.randomUUID()}").apply { mkdirs() }
        kek = KeystoreKek.forPurpose(context, purpose)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        runCatching { keyStore.deleteEntry("warden.kek.$purpose") }
    }

    private fun newEnvelopeFile(context: String = "warden:test:v1") = EnvelopeFile(
        dataFile = File(testDir, "test.envelope"),
        wrappedDekFile = File(testDir, "test.dek"),
        wrapper = kek,
        context = context.toByteArray(),
    )

    @Test
    fun writeThenReadRoundTripsThroughRealFile() {
        val plaintext = "Safeguard-Registry-Sollzustand (Testinhalt B.4)".toByteArray()
        val envelopeFile = newEnvelopeFile()

        envelopeFile.write(plaintext)
        val recovered = envelopeFile.read()

        assertArrayEquals(plaintext, recovered)
    }

    @Test
    fun secondWriteReusesExistingDek() {
        val envelopeFile = newEnvelopeFile()
        envelopeFile.write("erster Inhalt".toByteArray())
        val dekFile = File(testDir, "test.dek")
        val wrappedDekAfterFirstWrite = dekFile.readBytes()

        envelopeFile.write("zweiter Inhalt".toByteArray())

        assertArrayEquals(
            "der gewrappte DEK darf sich zwischen zwei Schreibvorgängen derselben Datei nicht ändern",
            wrappedDekAfterFirstWrite,
            dekFile.readBytes(),
        )
        assertArrayEquals("zweiter Inhalt".toByteArray(), envelopeFile.read())
    }

    @Test
    fun readFailsOnMismatchedContextAad() {
        newEnvelopeFile(context = "warden:registry:v1").write("geheim".toByteArray())

        try {
            newEnvelopeFile(context = "warden:log:v1").read()
            throw AssertionError("read() hätte bei falschem AAD-Kontext fehlschlagen müssen")
        } catch (expected: OpenException) {
            // erwartet — Fail-Safe (Invariante 6): falscher Zweckkontext darf nie Klartext liefern.
        }
    }

    @Test
    fun readFailsOnTamperedDataFile() {
        val envelopeFile = newEnvelopeFile()
        envelopeFile.write("unverändert bleiben".toByteArray())

        val dataFile = File(testDir, "test.envelope")
        val bytes = dataFile.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        dataFile.writeBytes(bytes)

        try {
            envelopeFile.read()
            throw AssertionError("read() hätte bei manipulierter Envelope-Datei fehlschlagen müssen")
        } catch (expected: OpenException) {
            // erwartet.
        }
    }

    @Test
    fun hasDekReflectsPersistedState() {
        val envelopeFile = newEnvelopeFile()
        assertTrue("vor dem ersten Schreiben darf kein DEK existieren", !envelopeFile.hasDek())

        envelopeFile.write("Inhalt".toByteArray())

        assertTrue("nach dem ersten Schreiben muss ein gewrappter DEK persistiert sein", envelopeFile.hasDek())
    }

    @Test
    fun readDoesNotCreateDekWhenDekFileIsMissing() {
        val envelopeFile = newEnvelopeFile()
        envelopeFile.write("Inhalt".toByteArray())
        File(testDir, "test.dek").delete()

        try {
            envelopeFile.read()
            throw AssertionError("read() hätte ohne DEK fehlschlagen müssen")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("DEK-Datei existiert nicht"))
        }
        assertTrue("read() darf keine neue DEK anlegen", !envelopeFile.hasDek())
    }

    @Test
    fun writeLeavesNoTempFilesBehind() {
        // Meilenstein B.6: write() schreibt seit dem atomaren Rename (siehe EnvelopeFile-
        // Klassendoc) über eine Temp-Datei im selben Verzeichnis — die darf nach jedem
        // erfolgreichen write() nicht mehr existieren, egal ob Daten- oder DEK-Datei.
        val envelopeFile = newEnvelopeFile()

        envelopeFile.write("erster Inhalt".toByteArray())
        envelopeFile.write("zweiter Inhalt".toByteArray())

        val leftoverTempFiles = testDir.listFiles { file -> file.name.contains(".tmp-") }.orEmpty()
        assertTrue(
            "keine Temp-Dateien nach erfolgreichem write() erwartet, gefunden: ${leftoverTempFiles.map { it.name }}",
            leftoverTempFiles.isEmpty(),
        )
    }
}
