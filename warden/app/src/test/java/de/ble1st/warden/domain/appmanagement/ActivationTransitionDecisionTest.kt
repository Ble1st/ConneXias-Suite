package de.ble1st.warden.domain.appmanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationTransitionDecisionTest {

    @Test
    fun firstEverScanFlagsNothing() {
        // previouslyActive == null markiert "keine Historie" — ohne diesen Schutz wäre jeder
        // bereits länger aktive Admin (inkl. Wardens eigenem) beim allerersten Lauf ein
        // Falsch-Fund.
        val result = ActivationTransitionDecision.evaluate(
            previouslyActive = null,
            currentlyActive = setOf("de.ble1st.warden", "com.example.longstanding"),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun packageActivatedSinceLastScanIsFlagged() {
        val result = ActivationTransitionDecision.evaluate(
            previouslyActive = setOf("com.example.old"),
            currentlyActive = setOf("com.example.old", "com.example.new"),
        )
        assertEquals(setOf("com.example.new"), result)
    }

    @Test
    fun packageStillActiveFromBeforeIsNotFlaggedAgain() {
        val result = ActivationTransitionDecision.evaluate(
            previouslyActive = setOf("com.example.old"),
            currentlyActive = setOf("com.example.old"),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun packageDeactivatedIsNotFlagged() {
        val result = ActivationTransitionDecision.evaluate(
            previouslyActive = setOf("com.example.old"),
            currentlyActive = emptySet(),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun emptyPreviousHistoryStillFlagsNewActivations() {
        // Anders als previouslyActive == null: eine *bekannte, aber leere* Historie (z. B. nach
        // dem allerersten Lauf, an dem nichts aktiv war) ist ein valider Vergleichspunkt.
        val result = ActivationTransitionDecision.evaluate(
            previouslyActive = emptySet(),
            currentlyActive = setOf("com.example.new"),
        )
        assertEquals(setOf("com.example.new"), result)
    }
}
