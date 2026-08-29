package de.ble1st.warden.domain.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Befund Q-3 (2026-08-28): die Aufbewahrungsgrenze darf die Manipulationserkennung nicht
 * aushebeln. Genau diese Grenze zwischen "absichtlich verworfen" und "heimlich gelöscht" prüfen
 * die Fälle hier.
 */
class HashChainRetentionDecisionTest {

    private val anchorHash = ByteArray(32) { 0x7A }

    @Test
    fun emptyChainStartsAtGenesis() {
        assertEquals(
            HashChainRetentionDecision.Start.Genesis,
            HashChainRetentionDecision.startOf(false, 0L, false, 0L, ByteArray(0)),
        )
    }

    @Test
    fun untruncatedChainStartsAtGenesis() {
        assertEquals(
            HashChainRetentionDecision.Start.Genesis,
            HashChainRetentionDecision.startOf(true, 0L, false, 0L, ByteArray(0)),
        )
    }

    /** Der Absturzfall zwischen "Anker geschrieben" und "Dateien gelöscht": der Anker läuft der
     * Wirklichkeit voraus, die Kette beginnt aber weiterhin bei 0 — das ist kein Bruch. */
    @Test
    fun anchorAheadOfAnUntruncatedChainIsNotABreak() {
        assertEquals(
            HashChainRetentionDecision.Start.Genesis,
            HashChainRetentionDecision.startOf(true, 0L, true, 499L, anchorHash),
        )
    }

    @Test
    fun matchingAnchorExplainsTheGap() {
        assertEquals(
            HashChainRetentionDecision.Start.AfterDiscarded(anchorHash),
            HashChainRetentionDecision.startOf(true, 500L, true, 499L, anchorHash),
        )
    }

    /** Der eigentliche Angriffsfall: die ältesten Segmente sind weg, aber niemand hat einen Anker
     * hinterlassen. */
    @Test
    fun truncationWithoutAnAnchorStaysUnexplained() {
        val start = HashChainRetentionDecision.startOf(true, 500L, false, 0L, ByteArray(0))
        assertTrue(start is HashChainRetentionDecision.Start.Unexplained)
    }

    /** Ein Anker, der eine *andere* Lücke erklärt als die vorgefundene, zählt nicht: sonst
     * ließe sich mit einem einmal gültigen Anker beliebig viel mehr wegschneiden. */
    @Test
    fun anchorForADifferentGapStaysUnexplained() {
        val start = HashChainRetentionDecision.startOf(true, 1500L, true, 499L, anchorHash)
        assertTrue(start is HashChainRetentionDecision.Start.Unexplained)
    }

    @Test
    fun discardsOnlyWhatExceedsTheLimit() {
        assertEquals(0, HashChainRetentionDecision.segmentsToDiscard(20, 20))
        assertEquals(0, HashChainRetentionDecision.segmentsToDiscard(3, 20))
        assertEquals(3, HashChainRetentionDecision.segmentsToDiscard(23, 20))
    }

    /** Ohne konfigurierte Grenze wird nie etwas verworfen — das bisherige Verhalten. */
    @Test
    fun noLimitDiscardsNothing() {
        assertEquals(0, HashChainRetentionDecision.segmentsToDiscard(9999, null))
    }
}
