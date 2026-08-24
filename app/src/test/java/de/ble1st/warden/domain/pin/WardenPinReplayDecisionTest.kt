package de.ble1st.warden.domain.pin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WardenPinReplayDecisionTest {

    private val hashA = ByteArray(32) { 1 }
    private val hashB = ByteArray(32) { 2 }

    @Test
    fun missingBothIsRejected() {
        val result = WardenPinReplayDecision.evaluate(
            blobPresent = false,
            blobCounter = 0,
            blobHash = hashA,
            anchorPresent = false,
            anchorCounter = 0,
            anchorHash = hashA,
        )
        assertTrue(result is WardenPinReplayDecision.Result.Reject)
    }

    @Test
    fun blobMissingWithAnchorIsRejected() {
        val result = WardenPinReplayDecision.evaluate(
            blobPresent = false,
            blobCounter = 0,
            blobHash = hashA,
            anchorPresent = true,
            anchorCounter = 3,
            anchorHash = hashA,
        )
        assertTrue(result is WardenPinReplayDecision.Result.Reject)
    }

    @Test
    fun blobWithoutAnchorMigrates() {
        val result = WardenPinReplayDecision.evaluate(
            blobPresent = true,
            blobCounter = 2,
            blobHash = hashA,
            anchorPresent = false,
            anchorCounter = 0,
            anchorHash = ByteArray(32),
        )
        assertEquals(WardenPinReplayDecision.Result.AcceptAndWriteAnchor, result)
    }

    @Test
    fun matchingCounterAndHashIsAccepted() {
        val result = WardenPinReplayDecision.evaluate(
            blobPresent = true,
            blobCounter = 4,
            blobHash = hashA,
            anchorPresent = true,
            anchorCounter = 4,
            anchorHash = hashA,
        )
        assertEquals(WardenPinReplayDecision.Result.Accept, result)
    }

    @Test
    fun olderBlobIsRejected() {
        val result = WardenPinReplayDecision.evaluate(
            blobPresent = true,
            blobCounter = 1,
            blobHash = hashA,
            anchorPresent = true,
            anchorCounter = 2,
            anchorHash = hashB,
        )
        assertTrue(result is WardenPinReplayDecision.Result.Reject)
    }

    @Test
    fun sameCounterDifferentHashIsRejected() {
        val result = WardenPinReplayDecision.evaluate(
            blobPresent = true,
            blobCounter = 2,
            blobHash = hashA,
            anchorPresent = true,
            anchorCounter = 2,
            anchorHash = hashB,
        )
        assertTrue(result is WardenPinReplayDecision.Result.Reject)
    }

    @Test
    fun newerBlobRepairsAnchor() {
        val result = WardenPinReplayDecision.evaluate(
            blobPresent = true,
            blobCounter = 5,
            blobHash = hashB,
            anchorPresent = true,
            anchorCounter = 4,
            anchorHash = hashA,
        )
        assertEquals(WardenPinReplayDecision.Result.AcceptAndWriteAnchor, result)
    }
}
