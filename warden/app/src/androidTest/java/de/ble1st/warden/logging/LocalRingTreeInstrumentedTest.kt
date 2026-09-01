package de.ble1st.warden.logging

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Meilenstein B.5 — Abnahme (Konzept 19): "Timber-Fassade + `LocalRingTree` (verschlüsselter
 * Ringpuffer über B.4)". Instrumented statt JVM-Unit-Test aus demselben Grund wie
 * `EngineInstrumentedTest`/`EnvelopeFileInstrumentedTest` (`:core:crypto`) — `EnvelopeFile`
 * braucht den echten AndroidKeyStore und die native connexias-engine-`.so`.
 *
 * Ruft bewusst die öffentlichen `Tree.log(priority, message)`/`log(priority, t, message)`-
 * Überladungen direkt auf der (nicht bei `Timber.plant()` eingehängten) Instanz auf, statt über
 * den globalen `Timber`-Forest zu gehen — isoliert die Tests voneinander und vermeidet
 * Testreihenfolge-Abhängigkeiten durch globalen Timber-Zustand. Der `tag`-Parameter bleibt dabei
 * `null` (Timbers `Tree.tag`-Property liest nur explizit per `Timber.tag()` gesetzte Werte),
 * daher wird er hier nicht mitgeprüft.
 */
@RunWith(AndroidJUnit4::class)
class LocalRingTreeInstrumentedTest {

    private lateinit var testDir: File
    private lateinit var kek: KeystoreKek
    private val purpose = "test-ring-${UUID.randomUUID()}"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testDir = File(context.cacheDir, "ring-tree-test-${UUID.randomUUID()}").apply { mkdirs() }
        kek = KeystoreKek.forPurpose(context, purpose)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        runCatching { keyStore.deleteEntry("warden.kek.$purpose") }
    }

    private fun newEnvelopeFile() = EnvelopeFile(
        dataFile = File(testDir, "ring.envelope"),
        wrappedDekFile = File(testDir, "ring.dek"),
        wrapper = kek,
        context = "warden:log:ring:v1".toByteArray(),
    )

    @Test
    fun loggedLinesAppearInSnapshot() {
        val tree = LocalRingTree(newEnvelopeFile(), capacity = 10)

        tree.log(Log.DEBUG, "erste Zeile")
        tree.log(Log.WARN, "zweite Zeile")

        val snapshot = tree.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals("erste Zeile", snapshot[0].message)
        assertEquals("zweite Zeile", snapshot[1].message)
        assertEquals(Log.WARN, snapshot[1].priority)
    }

    @Test
    fun oldestEntriesAreEvictedBeyondCapacity() {
        val tree = LocalRingTree(newEnvelopeFile(), capacity = 3)

        repeat(5) { i -> tree.log(Log.INFO, "Zeile $i") }

        val snapshot = tree.snapshot()
        assertEquals(3, snapshot.size)
        assertEquals(listOf("Zeile 2", "Zeile 3", "Zeile 4"), snapshot.map { it.message })
    }

    @Test
    fun bufferSurvivesAcrossInstancesThroughRealFile() {
        val envelopeFile = newEnvelopeFile()
        val firstTree = LocalRingTree(envelopeFile, capacity = 10)
        firstTree.log(Log.ERROR, "überlebt Neustart")

        val secondTree = LocalRingTree(envelopeFile, capacity = 10)

        val snapshot = secondTree.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals("überlebt Neustart", snapshot[0].message)
    }

    @Test
    fun missingFileStartsWithEmptyBuffer() {
        val tree = LocalRingTree(newEnvelopeFile(), capacity = 10)

        assertTrue("ohne vorherige Datei muss der Puffer leer starten", tree.snapshot().isEmpty())
    }

    @Test
    fun throwableIsAppendedToMessage() {
        val tree = LocalRingTree(newEnvelopeFile(), capacity = 10)

        tree.log(Log.ERROR, IllegalStateException("kaputt"), "Fehler aufgetreten")

        val message = tree.snapshot().single().message
        assertTrue(message.contains("Fehler aufgetreten"))
        assertTrue(message.contains("IllegalStateException"))
    }
}
