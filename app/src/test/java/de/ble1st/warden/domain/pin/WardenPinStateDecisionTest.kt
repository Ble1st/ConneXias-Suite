package de.ble1st.warden.domain.pin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Kein WardenRollbackDecisionTest-Äquivalent mehr (Cross-APK-Zähler-/Hash-Spiegel-Vergleich
// entfällt, s. WardenPinStateDecision-Klassendoc) — nur noch LoadResult selbst zu testen.
class WardenPinStateDecisionTest {

    @Test
    fun reportsNotYetConfiguredWithoutCallingDecode() {
        var decodeCalled = false

        val result = WardenPinStateDecision.load(fileExists = false) {
            decodeCalled = true
            WardenPinBlob.genesis()
        }

        assertEquals(WardenPinStateDecision.LoadResult.NotYetConfigured, result)
        assertFalse("decode() darf bei fehlender Datei nicht aufgerufen werden", decodeCalled)
    }

    @Test
    fun reportsLoadedOnSuccessfulDecode() {
        val blob = WardenPinBlob.genesis().copy(counter = 1, pinHash = "phc-hash")

        val result = WardenPinStateDecision.load(fileExists = true) { blob }

        assertEquals(WardenPinStateDecision.LoadResult.Loaded(blob), result)
    }

    @Test
    fun reportsNotYetConfiguredOnEmptyPinHash() {
        val blob = WardenPinBlob.genesis().copy(counter = 1)

        val result = WardenPinStateDecision.load(fileExists = true) { blob }

        assertEquals(WardenPinStateDecision.LoadResult.NotYetConfigured, result)
    }

    @Test
    fun reportsCorruptedOnDecodeException() {
        val result = WardenPinStateDecision.load(fileExists = true) {
            throw IllegalArgumentException("kaputt")
        }

        assertTrue(result is WardenPinStateDecision.LoadResult.Corrupted)
    }
}
