package de.ble1st.warden.logging

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Meilenstein B.6 — Abnahme (Konzept 19): "Zentraler append-only Log-Store in Warden mit
 * Hash-Kette; Manipulationstest (Eintrag entfernen → Kette bricht → erkannt)". Instrumented
 * statt JVM-Unit-Test aus demselben Grund wie die übrigen `:core:crypto`/`:core:logging`-Tests
 * (echter AndroidKeyStore + native `.so`). Die reine Verkettungslogik selbst ist bereits
 * gerätefrei in [HashChainIntegrityTest] abgedeckt — hier geht es um den Ende-zu-Ende-Pfad durch
 * die echte Envelope-Persistenz (Keystore-Wrap, AEAD, atomarer Write).
 */
@RunWith(AndroidJUnit4::class)
class HashChainLogStoreInstrumentedTest {

    private lateinit var testDir: File
    private lateinit var kek: KeystoreKek
    private val purpose = "test-chain-${UUID.randomUUID()}"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testDir = File(context.cacheDir, "chain-log-test-${UUID.randomUUID()}").apply { mkdirs() }
        kek = KeystoreKek.forPurpose(context, purpose)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        runCatching { keyStore.deleteEntry("warden.kek.$purpose") }
    }

    private fun newEnvelopeFile() = EnvelopeFile(
        dataFile = File(testDir, "chain.envelope"),
        wrappedDekFile = File(testDir, "chain.dek"),
        wrapper = kek,
        context = "warden:log:chain:v1".toByteArray(),
    )

    @Test
    fun emptyStoreIsValid() {
        val store = HashChainLogStore(newEnvelopeFile())

        assertEquals(ChainVerificationResult.Valid(0L), store.verifyChainIntegrity())
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun appendedEntriesFormAValidChain() {
        val store = HashChainLogStore(newEnvelopeFile())

        store.append(priority = 3, tag = "Warden", message = "erster Eintrag")
        store.append(priority = 4, tag = "Warden", message = "zweiter Eintrag")
        store.append(priority = 6, tag = "Warden", message = "dritter Eintrag")

        val entries = store.entries()
        assertEquals(listOf("erster Eintrag", "zweiter Eintrag", "dritter Eintrag"), entries.map { it.message })
        assertEquals(listOf(0L, 1L, 2L), entries.map { it.sequence })
        assertEquals(ChainVerificationResult.Valid(3L), store.verifyChainIntegrity())
    }

    @Test
    fun chainSurvivesAcrossInstancesThroughRealFile() {
        val envelopeFile = newEnvelopeFile()
        val firstStore = HashChainLogStore(envelopeFile)
        firstStore.append(priority = 3, tag = "Warden", message = "vor Neustart")

        val secondStore = HashChainLogStore(envelopeFile)
        secondStore.append(priority = 3, tag = "Warden", message = "nach Neustart")

        val entries = secondStore.entries()
        assertEquals(listOf("vor Neustart", "nach Neustart"), entries.map { it.message })
        assertEquals(ChainVerificationResult.Valid(2L), secondStore.verifyChainIntegrity())
    }

    @Test
    fun removingAnEntryFromTheRealFileBreaksTheChain() {
        val envelopeFile = newEnvelopeFile()
        val store = HashChainLogStore(envelopeFile)
        store.append(priority = 3, tag = "Warden", message = "eins")
        store.append(priority = 3, tag = "Warden", message = "zwei")
        store.append(priority = 3, tag = "Warden", message = "drei")

        // Simuliert einen Angreifer, der die Verkettungsregel nicht kennt/respektiert: liest die
        // reale, entschlüsselte Kette, entfernt einen mittleren Eintrag und schreibt den Rest
        // unverändert zurück — durch dieselbe echte Keystore-/AEAD-Kette wie ein regulärer
        // append(), aber ohne die previousHash-Verweise nachzuziehen.
        val tampered = decodeEntries(envelopeFile.read()).toMutableList().apply { removeAt(1) }
        envelopeFile.write(encodeEntries(tampered))

        val result = store.verifyChainIntegrity()
        assertTrue(
            "Entfernen eines Eintrags in der echten Datei muss erkannt werden",
            result is ChainVerificationResult.Broken,
        )
    }

    @Test
    fun missingDataFileAfterDekExistsFailsClosed() {
        val store = HashChainLogStore(newEnvelopeFile())
        store.append(priority = 3, tag = "Warden", message = "eins")
        val dataFile = File(testDir, "chain.envelope")
        assertTrue(dataFile.delete())

        try {
            store.entries()
            throw AssertionError("entries() hätte bei fehlender Datenfile nach existierendem DEK fehlschlagen müssen")
        } catch (expected: IllegalStateException) {
            // erwartet — Fail-Safe (Invariante 6): ein verschwundenes Log darf nie als "leer" durchgehen.
        }
    }

    // Stabilisierung nach G (Log-Rotation, CLAUDE.md-Priorität 3 "wichtigster rein-technischer
    // Punkt für echten Langzeitbetrieb"): bewusst kleine `segmentCapacity`-Werte, um mehrere
    // Rotationen mit wenigen `append()`-Aufrufen real durch die echte Envelope-/Keystore-Grenze
    // zu erzwingen, statt 500+ Einträge in einem Instrumented Test anzuhäufen.

    @Test
    fun appendBeyondSegmentCapacityRotatesAndKeepsTheFullChainReadable() {
        val store = HashChainLogStore(newEnvelopeFile(), segmentCapacity = 2)

        repeat(5) { i -> store.append(priority = 3, tag = "Warden", message = "eintrag-$i") }

        val entries = store.entries()
        assertEquals((0 until 5).map { "eintrag-$it" }, entries.map { it.message })
        assertEquals((0L until 5L).toList(), entries.map { it.sequence })
        assertEquals(ChainVerificationResult.Valid(5L), store.verifyChainIntegrity())

        val archiveCount = testDir.listFiles { f -> f.name.contains(".archive.") }?.size ?: 0
        assertEquals("5 Einträge bei Kapazität 2 müssen zwei volle Segmente archivieren", 2, archiveCount)
    }

    @Test
    fun archivedSegmentsAreNeverRewrittenByLaterAppends() {
        val store = HashChainLogStore(newEnvelopeFile(), segmentCapacity = 2)
        store.append(priority = 3, tag = "Warden", message = "eins")
        store.append(priority = 3, tag = "Warden", message = "zwei")
        store.append(priority = 3, tag = "Warden", message = "drei") // löst die erste Rotation aus

        val archiveFile = testDir.listFiles { f -> f.name.contains(".archive.") }!!.single()
        val bytesAfterFirstRotation = archiveFile.readBytes()

        store.append(priority = 3, tag = "Warden", message = "vier")
        store.append(priority = 3, tag = "Warden", message = "fünf") // löst die zweite Rotation aus

        assertArrayEquals(
            "eine einmal archivierte Segment-Datei darf durch spätere Appends nie erneut verschlüsselt/geschrieben werden",
            bytesAfterFirstRotation,
            archiveFile.readBytes(),
        )
        assertEquals(5, store.entries().size)
        assertEquals(ChainVerificationResult.Valid(5L), store.verifyChainIntegrity())
    }

    @Test
    fun tamperingWithAnArchivedSegmentIsStillDetected() {
        val envelopeFile = newEnvelopeFile()
        val store = HashChainLogStore(envelopeFile, segmentCapacity = 2)
        store.append(priority = 3, tag = "Warden", message = "eins")
        store.append(priority = 3, tag = "Warden", message = "zwei")
        store.append(priority = 3, tag = "Warden", message = "drei") // "eins"/"zwei" jetzt archiviert

        val archiveFile = testDir.listFiles { f -> f.name.contains(".archive.") }!!.single()
        val archiveEnvelope = envelopeFile.sibling(archiveFile.name)
        val tampered = decodeEntries(archiveEnvelope.read()).toMutableList().apply { removeAt(0) }
        archiveEnvelope.write(encodeEntries(tampered))

        val result = store.verifyChainIntegrity()
        assertTrue(
            "Manipulation eines bereits archivierten Segments muss weiterhin erkannt werden — " +
                "Rotation darf keinen blinden Fleck für die Manipulationserkennung öffnen",
            result is ChainVerificationResult.Broken,
        )
    }

    @Test
    fun crashBetweenArchiveWriteAndActiveClearIsIdempotentOnRetry() {
        val envelopeFile = newEnvelopeFile()
        val store = HashChainLogStore(envelopeFile, segmentCapacity = 2)
        store.append(priority = 3, tag = "Warden", message = "e0")
        store.append(priority = 3, tag = "Warden", message = "e1") // aktives Segment voll, noch nicht rotiert

        // Simuliert einen Absturz genau zwischen "Archiv geschrieben" und "aktives Segment
        // geleert" (s. HashChainLogStore-Klassendoc): das Archiv für die ersten zwei Einträge
        // existiert unter seinem deterministischen Namen bereits vollständig, das aktive Segment
        // enthält aber weiterhin unverändert dieselben zwei Einträge.
        val archiveName = archiveFileName("chain", ".envelope", 1L)
        envelopeFile.sibling(archiveName).write(encodeEntries(decodeEntries(envelopeFile.read())))

        val third = store.append(priority = 3, tag = "Warden", message = "e2")

        assertEquals(2L, third.sequence)
        assertEquals(listOf("e0", "e1", "e2"), store.entries().map { it.message })
        assertEquals(ChainVerificationResult.Valid(3L), store.verifyChainIntegrity())
        assertEquals(
            "der simulierte Absturz-Retry darf kein zweites, überlappendes Archiv anlegen",
            1,
            testDir.listFiles { f -> f.name.contains(".archive.") }?.size,
        )
    }

    // ---- Aufbewahrungsgrenze (2026-08-28, Befund Q-3) ----

    private fun newRetentionAnchorFile() = EnvelopeFile(
        dataFile = File(testDir, "chain.retention.envelope"),
        wrappedDekFile = File(testDir, "chain.retention.dek"),
        wrapper = kek,
        context = "warden:log:retention:v1".toByteArray(),
    )

    @Test
    fun retentionDiscardsOldestSegmentsAndKeepsTheChainVerifiable() {
        val store = HashChainLogStore(
            newEnvelopeFile(),
            segmentCapacity = 2,
            retentionAnchorFile = newRetentionAnchorFile(),
            keepArchivedSegments = 1,
        )
        repeat(7) { store.append(priority = 3, tag = "Warden", message = "e$it") }

        // 7 Einträge bei Kapazität 2: drei Archive entstünden, eines darf bleiben.
        assertEquals(1, testDir.listFiles { f -> f.name.contains(".archive.") }?.size)
        // Genau das ist der Punkt: verworfen ist verworfen, aber die Kette bleibt prüfbar.
        assertTrue(
            "Aufbewahrungsgrenze darf die Manipulationserkennung nicht aushebeln",
            store.verifyChainIntegrity() is ChainVerificationResult.Valid,
        )
        assertEquals(3L, store.discardedThroughSequence())
        assertEquals(listOf("e4", "e5", "e6"), store.entries().map { it.message })
    }

    /** Ohne Retention-Anker sieht eine Kürzung genauso aus wie ein Angriff — und muss es auch. */
    @Test
    fun truncationWithoutTheAnchorStillBreaksVerification() {
        val store = HashChainLogStore(newEnvelopeFile(), segmentCapacity = 2)
        repeat(6) { store.append(priority = 3, tag = "Warden", message = "e$it") }

        val oldest = testDir.listFiles { f -> f.name.contains(".archive.") }!!.sortedBy { it.name }.first()
        assertTrue(oldest.delete())

        assertTrue(
            "eine Kürzung ohne Anker muss weiterhin als Bruch gemeldet werden",
            store.verifyChainIntegrity() is ChainVerificationResult.Broken,
        )
    }

    @Test
    fun retentionIsOffByDefault() {
        val store = HashChainLogStore(newEnvelopeFile(), segmentCapacity = 2)
        repeat(7) { store.append(priority = 3, tag = "Warden", message = "e$it") }

        assertEquals(3, testDir.listFiles { f -> f.name.contains(".archive.") }?.size)
        assertEquals(null, store.discardedThroughSequence())
    }
}
