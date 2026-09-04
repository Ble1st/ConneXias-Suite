package de.ble1st.warden.domain.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimChangeDecisionTest {

    @Test
    fun unreadableNeverTriggersAnything() {
        assertEquals(
            SimChangeOutcome.NotReadable,
            SimChangeDecision.evaluate(storedFingerprint = "abc", currentFingerprint = null),
        )
        assertEquals(
            SimChangeOutcome.NotReadable,
            SimChangeDecision.evaluate(storedFingerprint = null, currentFingerprint = null),
        )
    }

    @Test
    fun firstReadingOnlyEstablishesBaseline() {
        val outcome = SimChangeDecision.evaluate(storedFingerprint = null, currentFingerprint = "abc")
        assertEquals(SimChangeOutcome.BaselineEstablished("abc"), outcome)
    }

    @Test
    fun sameSimIsUnchanged() {
        assertEquals(
            SimChangeOutcome.Unchanged,
            SimChangeDecision.evaluate(storedFingerprint = "abc", currentFingerprint = "abc"),
        )
    }

    @Test
    fun differentSimIsAChangeButNotARemoval() {
        val outcome = SimChangeDecision.evaluate(storedFingerprint = "abc", currentFingerprint = "xyz")
        assertTrue(outcome is SimChangeOutcome.Changed)
        outcome as SimChangeOutcome.Changed
        assertEquals("xyz", outcome.newFingerprint)
        assertTrue(!outcome.simRemoved)
    }

    /** Entfernte SIM ist ein echtes Signal, kein Messfehler — anders als [SimChangeOutcome.NotReadable]. */
    @Test
    fun removedSimIsAChangeMarkedAsRemoval() {
        val outcome = SimChangeDecision.evaluate(
            storedFingerprint = "abc",
            currentFingerprint = SimChangeDecision.NO_SIM_FINGERPRINT,
        )
        assertTrue(outcome is SimChangeOutcome.Changed)
        assertTrue((outcome as SimChangeOutcome.Changed).simRemoved)
    }

    /** Von "keine SIM" zurück auf eine eingelegte SIM ist ebenfalls ein Wechsel. */
    @Test
    fun insertingASimAfterNoneIsAlsoAChange() {
        val outcome = SimChangeDecision.evaluate(
            storedFingerprint = SimChangeDecision.NO_SIM_FINGERPRINT,
            currentFingerprint = "abc",
        )
        assertTrue(outcome is SimChangeOutcome.Changed)
        assertTrue(!(outcome as SimChangeOutcome.Changed).simRemoved)
    }
}
