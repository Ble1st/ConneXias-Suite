package de.ble1st.warden.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Meilenstein B.6 — Abnahme (Konzept 19): "Manipulationstest (Eintrag entfernen → Kette bricht
 * → erkannt)". Reiner JVM-Unit-Test der Verkettungslogik (`verifyChainIntegrity`,
 * `computeHash`) — bewusst ohne `EnvelopeFile`/AndroidKeyStore, weil die Hash-Ketten-Garantie
 * selbst kein Android braucht (siehe Kommentar an der reinen `verifyChainIntegrity(chain)`-
 * Funktion in HashChainLogStore.kt). Der Ende-zu-Ende-Pfad durch die echte Envelope-Persistenz
 * wird separat instrumented abgedeckt (analog zu Engine-/EnvelopeFile-Tests aus B.1–B.4).
 */
class HashChainIntegrityTest {

    /** Baut eine gültige Kette nach, ohne EnvelopeFile — spiegelt exakt die Verkettungslogik
     * aus `HashChainLogStore.append`. */
    private fun buildValidChain(vararg messages: String): List<LogEntry> {
        val chain = mutableListOf<LogEntry>()
        for ((index, message) in messages.withIndex()) {
            val previousHash = chain.lastOrNull()?.hash ?: HashChainLogStore.GENESIS_HASH
            val sequence = index.toLong()
            val timestamp = 1_000L + index
            val hash = computeHash(previousHash, sequence, timestamp, priority = 4, tag = "test", message = message)
            chain.add(LogEntry(sequence, timestamp, 4, "test", message, previousHash, hash))
        }
        return chain
    }

    @Test
    fun emptyChainIsValid() {
        val result = verifyChainIntegrity(emptyList())
        assertEquals(ChainVerificationResult.Valid(0L), result)
    }

    @Test
    fun intactChainIsValid() {
        val chain = buildValidChain("eins", "zwei", "drei")

        val result = verifyChainIntegrity(chain)

        assertEquals(ChainVerificationResult.Valid(3L), result)
    }

    @Test
    fun removingAMiddleEntryBreaksTheChain() {
        val chain = buildValidChain("eins", "zwei", "drei").toMutableList()
        chain.removeAt(1) // "zwei" entfernen — "drei" verweist danach auf den falschen Vorgänger

        val result = verifyChainIntegrity(chain)

        assertTrue(
            "Entfernen eines mittleren Eintrags muss als Bruch erkannt werden",
            result is ChainVerificationResult.Broken,
        )
        assertEquals(2L, (result as ChainVerificationResult.Broken).atSequence)
    }

    @Test
    fun tamperingWithAnEntrysMessageBreaksTheChain() {
        val chain = buildValidChain("eins", "zwei", "drei").toMutableList()
        chain[1] = chain[1].copy(message = "manipuliert") // hash bleibt absichtlich der alte

        val result = verifyChainIntegrity(chain)

        assertTrue(
            "verändertes Eintrag-Feld muss als Bruch erkannt werden",
            result is ChainVerificationResult.Broken,
        )
        assertEquals(1L, (result as ChainVerificationResult.Broken).atSequence)
    }

    @Test
    fun swappingTwoEntriesBreaksTheChain() {
        val chain = buildValidChain("eins", "zwei", "drei").toMutableList()
        val tmp = chain[0]
        chain[0] = chain[1]
        chain[1] = tmp

        val result = verifyChainIntegrity(chain)

        assertTrue("Vertauschen zweier Einträge muss als Bruch erkannt werden", result is ChainVerificationResult.Broken)
    }

    // Stabilisierung nach G (Log-Rotation): reine Namensschema-Prüfung für
    // HashChainLogStore.archiveFileName — die eigentliche Rotationslogik selbst braucht eine
    // echte EnvelopeFile (instrumented, s. HashChainLogStoreInstrumentedTest), aber das
    // deterministische Namensschema, auf dem die Absturz-Idempotenz aufbaut, ist reine,
    // gerätefreie Textverarbeitung.

    @Test
    fun archiveFileNameEmbedsBaseNameSequenceAndExtension() {
        val name = archiveFileName("log", ".envelope", 42L)

        assertEquals("log.archive.0000000000000000042.envelope", name)
    }

    @Test
    fun archiveFileNameIsDeterministicForTheSameInputs() {
        assertEquals(
            archiveFileName("log", ".envelope", 499L),
            archiveFileName("log", ".envelope", 499L),
        )
    }

    @Test
    fun archiveFileNamesSortLexicographicallyLikeTheirNumericSequence() {
        val small = archiveFileName("log", ".envelope", 7L)
        val large = archiveFileName("log", ".envelope", 12L)

        assertTrue(
            "Nullpadding muss lexikografische und numerische Ordnung angleichen (7 < 12)",
            small < large,
        )
    }
}
