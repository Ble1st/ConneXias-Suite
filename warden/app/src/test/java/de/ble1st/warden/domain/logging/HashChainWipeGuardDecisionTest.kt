package de.ble1st.warden.domain.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashChainWipeGuardDecisionTest {

    private val hashA = ByteArray(32) { 1 }
    private val hashB = ByteArray(32) { 2 }

    @Test
    fun noAnchorYetIsAccepted() {
        val result = HashChainWipeGuardDecision.evaluate(
            chainPresent = true,
            chainTailSequence = 3,
            chainTailHash = hashA,
            anchorPresent = false,
            anchorSequence = 0,
            anchorHash = ByteArray(0),
        )
        assertEquals(HashChainWipeGuardDecision.Result.Accept, result)
    }

    @Test
    fun freshEmptyChainWithoutAnchorIsAccepted() {
        val result = HashChainWipeGuardDecision.evaluate(
            chainPresent = false,
            chainTailSequence = -1,
            chainTailHash = ByteArray(0),
            anchorPresent = false,
            anchorSequence = 0,
            anchorHash = ByteArray(0),
        )
        assertEquals(HashChainWipeGuardDecision.Result.Accept, result)
    }

    @Test
    fun emptyChainWithAnchorIsFullWipeRejected() {
        val result = HashChainWipeGuardDecision.evaluate(
            chainPresent = false,
            chainTailSequence = -1,
            chainTailHash = ByteArray(0),
            anchorPresent = true,
            anchorSequence = 41,
            anchorHash = hashA,
        )
        assertTrue(result is HashChainWipeGuardDecision.Result.Reject)
    }

    @Test
    fun chainBehindAnchorIsRejected() {
        // e.g. a newer segment was deleted, leaving an older, still internally consistent tail.
        val result = HashChainWipeGuardDecision.evaluate(
            chainPresent = true,
            chainTailSequence = 10,
            chainTailHash = hashA,
            anchorPresent = true,
            anchorSequence = 41,
            anchorHash = hashB,
        )
        assertTrue(result is HashChainWipeGuardDecision.Result.Reject)
    }

    @Test
    fun matchingTailIsAccepted() {
        val result = HashChainWipeGuardDecision.evaluate(
            chainPresent = true,
            chainTailSequence = 41,
            chainTailHash = hashA,
            anchorPresent = true,
            anchorSequence = 41,
            anchorHash = hashA,
        )
        assertEquals(HashChainWipeGuardDecision.Result.Accept, result)
    }

    @Test
    fun sameSequenceDifferentHashIsRejected() {
        val result = HashChainWipeGuardDecision.evaluate(
            chainPresent = true,
            chainTailSequence = 41,
            chainTailHash = hashB,
            anchorPresent = true,
            anchorSequence = 41,
            anchorHash = hashA,
        )
        assertTrue(result is HashChainWipeGuardDecision.Result.Reject)
    }

    @Test
    fun chainAheadOfAnchorIsAccepted() {
        // Normal growth since the anchor was last written (or a crash right after the data
        // write, before the anchor write — s. HashChainLogStore-Klassendoc "Wipe-Guard").
        val result = HashChainWipeGuardDecision.evaluate(
            chainPresent = true,
            chainTailSequence = 42,
            chainTailHash = hashB,
            anchorPresent = true,
            anchorSequence = 41,
            anchorHash = hashA,
        )
        assertEquals(HashChainWipeGuardDecision.Result.Accept, result)
    }
}
