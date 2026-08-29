package de.ble1st.warden.domain.cellsecurity

import de.ble1st.warden.domain.appmanagement.ThreatSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CellSecurityDecisionTest {

    private fun cell(
        mcc: String? = "262",
        mnc: String? = "01",
        cellId: Long? = 1L,
        areaCode: Int? = 100,
        generation: CellGeneration = CellGeneration.LTE_4G,
        signalDbm: Int? = -80,
    ) = CellObservation(mcc, mnc, cellId, areaCode, generation, signalDbm)

    @Test
    fun unreadableNeverTriggersAnything() {
        assertEquals(CellSecurityOutcome.NotReadable, CellSecurityDecision.evaluate(previous = cell(), current = null))
        assertEquals(CellSecurityOutcome.NotReadable, CellSecurityDecision.evaluate(previous = null, current = null))
    }

    @Test
    fun firstReadingOnlyEstablishesBaseline() {
        val observation = cell()
        val outcome = CellSecurityDecision.evaluate(previous = null, current = observation)
        assertEquals(CellSecurityOutcome.BaselineEstablished(observation), outcome)
    }

    @Test
    fun identicalCellIsUnchanged() {
        val observation = cell()
        val outcome = CellSecurityDecision.evaluate(previous = observation, current = observation)
        assertEquals(CellSecurityOutcome.Unchanged(observation), outcome)
    }

    /** Normaler Zellwechsel während der Fahrt: neue Zell-ID UND neuer Gebietscode zusammen —
     * das ist der Alltagsfall, keine Auffälligkeit. */
    @Test
    fun ordinaryCellHandoverIsUnchanged() {
        val previous = cell(cellId = 1L, areaCode = 100)
        val current = cell(cellId = 2L, areaCode = 200)
        assertEquals(CellSecurityOutcome.Unchanged(current), CellSecurityDecision.evaluate(previous, current))
    }

    /** Der verlässlichste Einzelindikator: reicht allein für CRITICAL. */
    @Test
    fun areaCodeChangeOnSameCellIdIsCriticalAlone() {
        val previous = cell(cellId = 1L, areaCode = 100)
        val current = cell(cellId = 1L, areaCode = 999)
        val outcome = CellSecurityDecision.evaluate(previous, current)
        assertTrue(outcome is CellSecurityOutcome.Suspicious)
        outcome as CellSecurityOutcome.Suspicious
        assertEquals(setOf(CellSecurityIndicator.AREA_CODE_CHANGED_SAME_CELL), outcome.indicators)
        assertEquals(ThreatSeverity.CRITICAL, outcome.severity)
    }

    @Test
    fun generationDowngradeAloneIsOnlyWarning() {
        val previous = cell(generation = CellGeneration.LTE_4G)
        val current = cell(generation = CellGeneration.GSM_2G, cellId = 2L, areaCode = 200)
        val outcome = CellSecurityDecision.evaluate(previous, current)
        assertTrue(outcome is CellSecurityOutcome.Suspicious)
        outcome as CellSecurityOutcome.Suspicious
        assertEquals(setOf(CellSecurityIndicator.GENERATION_DOWNGRADE), outcome.indicators)
        assertEquals(ThreatSeverity.WARNING, outcome.severity)
    }

    @Test
    fun generationUpgradeIsNotADowngrade() {
        val previous = cell(generation = CellGeneration.GSM_2G)
        val current = cell(generation = CellGeneration.LTE_4G, cellId = 2L, areaCode = 200)
        assertEquals(CellSecurityOutcome.Unchanged(current), CellSecurityDecision.evaluate(previous, current))
    }

    @Test
    fun missingAreaCodeAloneIsOnlyWarning() {
        val previous = cell(areaCode = 100)
        val current = cell(cellId = 2L, areaCode = null)
        val outcome = CellSecurityDecision.evaluate(previous, current)
        assertTrue(outcome is CellSecurityOutcome.Suspicious)
        outcome as CellSecurityOutcome.Suspicious
        assertEquals(setOf(CellSecurityIndicator.INVALID_AREA_CODE), outcome.indicators)
        assertEquals(ThreatSeverity.WARNING, outcome.severity)
    }

    @Test
    fun suddenSignalJumpFromWeakReceptionAloneIsOnlyWarning() {
        val previous = cell(cellId = 2L, areaCode = 200, signalDbm = -100)
        val current = cell(cellId = 3L, areaCode = 300, signalDbm = -60)
        val outcome = CellSecurityDecision.evaluate(previous, current)
        assertTrue(outcome is CellSecurityOutcome.Suspicious)
        outcome as CellSecurityOutcome.Suspicious
        assertEquals(setOf(CellSecurityIndicator.SIGNAL_JUMP), outcome.indicators)
        assertEquals(ThreatSeverity.WARNING, outcome.severity)
    }

    /** Ein Sprung zwischen zwei bereits guten Werten ist ein normaler Handover, keine Auffälligkeit. */
    @Test
    fun signalJumpBetweenTwoStrongReadingsIsNotFlagged() {
        val previous = cell(cellId = 2L, areaCode = 200, signalDbm = -70)
        val current = cell(cellId = 3L, areaCode = 300, signalDbm = -40)
        assertEquals(CellSecurityOutcome.Unchanged(current), CellSecurityDecision.evaluate(previous, current))
    }

    /** Zwei gleichzeitige schwächere Indikatoren verstärken sich zu CRITICAL. */
    @Test
    fun twoWeakIndicatorsTogetherEscalateToCritical() {
        val previous = cell(generation = CellGeneration.LTE_4G, cellId = 2L, areaCode = 200, signalDbm = -100)
        val current = cell(generation = CellGeneration.GSM_2G, cellId = 3L, areaCode = 300, signalDbm = -60)
        val outcome = CellSecurityDecision.evaluate(previous, current)
        assertTrue(outcome is CellSecurityOutcome.Suspicious)
        outcome as CellSecurityOutcome.Suspicious
        assertEquals(
            setOf(CellSecurityIndicator.GENERATION_DOWNGRADE, CellSecurityIndicator.SIGNAL_JUMP),
            outcome.indicators,
        )
        assertEquals(ThreatSeverity.CRITICAL, outcome.severity)
    }
}
