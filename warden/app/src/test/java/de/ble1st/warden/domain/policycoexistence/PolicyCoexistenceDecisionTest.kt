package de.ble1st.warden.domain.policycoexistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyCoexistenceDecisionTest {

    private fun record(id: String, outcome: PolicyUpdateOutcome, at: Long) =
        PolicyConflictRecord(policyIdentifier = id, outcome = outcome, timestampMillis = at)

    @Test
    fun `leere Liste meldet weder Probleme noch eine je eingetroffene Rueckmeldung`() {
        assertTrue(PolicyCoexistenceDecision.currentProblems(emptyList()).isEmpty())
        assertFalse(PolicyCoexistenceDecision.hasEverReported(emptyList()))
    }

    @Test
    fun `erfolgreich gesetzte Richtlinie ist kein Problem, zaehlt aber als Rueckmeldung`() {
        val records = listOf(record("camera", PolicyUpdateOutcome.GESETZT, 1))
        assertTrue(PolicyCoexistenceDecision.currentProblems(records).isEmpty())
        assertTrue(PolicyCoexistenceDecision.hasEverReported(records))
    }

    @Test
    fun `Konflikt mit anderem Admin erscheint als Problem`() {
        val records = listOf(record("camera", PolicyUpdateOutcome.KONFLIKT_ANDERER_ADMIN, 5))
        val problems = PolicyCoexistenceDecision.currentProblems(records)
        assertEquals(1, problems.size)
        assertEquals("camera", problems.single().policyIdentifier)
    }

    /** Der Kernpunkt der Klasse: ein behobener Konflikt darf nicht als offener stehenbleiben. */
    @Test
    fun `spaeterer Erfolg loest einen frueheren Konflikt derselben Richtlinie ab`() {
        val records = listOf(
            record("camera", PolicyUpdateOutcome.KONFLIKT_ANDERER_ADMIN, 5),
            record("camera", PolicyUpdateOutcome.GESETZT, 10),
        )
        assertTrue(PolicyCoexistenceDecision.currentProblems(records).isEmpty())
    }

    @Test
    fun `spaeterer Konflikt ueberschreibt einen frueheren Erfolg`() {
        val records = listOf(
            record("camera", PolicyUpdateOutcome.GESETZT, 5),
            record("camera", PolicyUpdateOutcome.KONFLIKT_ANDERER_ADMIN, 10),
        )
        assertEquals(1, PolicyCoexistenceDecision.currentProblems(records).size)
    }

    /** Die Eingabereihenfolge darf nichts entscheiden — der Store liefert seine Einträge aus einem
     * `StringSet`, also ohne verlässliche Ordnung. */
    @Test
    fun `nur der Zeitstempel entscheidet, nicht die Reihenfolge in der Liste`() {
        val records = listOf(
            record("camera", PolicyUpdateOutcome.GESETZT, 10),
            record("camera", PolicyUpdateOutcome.KONFLIKT_ANDERER_ADMIN, 5),
        )
        assertTrue(PolicyCoexistenceDecision.currentProblems(records).isEmpty())
    }

    @Test
    fun `verschiedene Richtlinien werden unabhaengig voneinander bewertet`() {
        val records = listOf(
            record("camera", PolicyUpdateOutcome.GESETZT, 10),
            record("usb", PolicyUpdateOutcome.HARDWARE_GRENZE, 11),
            record("locktask", PolicyUpdateOutcome.KONFLIKT_ANDERER_ADMIN, 12),
        )
        val problems = PolicyCoexistenceDecision.currentProblems(records)
        assertEquals(listOf("locktask", "usb"), problems.map { it.policyIdentifier })
    }

    @Test
    fun `Probleme kommen neueste zuerst`() {
        val records = listOf(
            record("a", PolicyUpdateOutcome.UNBEKANNT, 1),
            record("b", PolicyUpdateOutcome.SPEICHERGRENZE, 99),
            record("c", PolicyUpdateOutcome.HARDWARE_GRENZE, 50),
        )
        assertEquals(listOf("b", "c", "a"), PolicyCoexistenceDecision.currentProblems(records).map { it.policyIdentifier })
    }

    /** Alle vier Fehlerfälle müssen als Problem gelten, die beiden Erfolgsfälle nicht — sonst
     * verschwände ein neuer Fall stillschweigend aus der Anzeige. */
    @Test
    fun `Problemkennzeichnung der Ergebnisse ist vollstaendig`() {
        assertFalse(PolicyUpdateOutcome.GESETZT.isProblem)
        assertFalse(PolicyUpdateOutcome.ZURUECKGENOMMEN.isProblem)
        assertTrue(PolicyUpdateOutcome.KONFLIKT_ANDERER_ADMIN.isProblem)
        assertTrue(PolicyUpdateOutcome.HARDWARE_GRENZE.isProblem)
        assertTrue(PolicyUpdateOutcome.SPEICHERGRENZE.isProblem)
        assertTrue(PolicyUpdateOutcome.UNBEKANNT.isProblem)
    }
}
