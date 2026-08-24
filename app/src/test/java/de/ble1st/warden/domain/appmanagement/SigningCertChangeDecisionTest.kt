package de.ble1st.warden.domain.appmanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningCertChangeDecisionTest {

    @Test
    fun unknownPackageIsNeverFlagged() {
        // Kein Eintrag in previousFingerprints -> keine Baseline zum Vergleichen, nur ein neuer
        // Eintrag, kein "geändert"-Fund.
        val result = SigningCertChangeDecision.evaluate(
            previousFingerprints = emptyMap(),
            currentFingerprints = mapOf("com.example.new" to "abc"),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun unchangedFingerprintIsNotFlagged() {
        val result = SigningCertChangeDecision.evaluate(
            previousFingerprints = mapOf("com.example.app" to "abc"),
            currentFingerprints = mapOf("com.example.app" to "abc"),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun changedFingerprintIsFlagged() {
        val result = SigningCertChangeDecision.evaluate(
            previousFingerprints = mapOf("com.example.app" to "abc"),
            currentFingerprints = mapOf("com.example.app" to "xyz"),
        )
        assertEquals(setOf("com.example.app"), result)
    }

    @Test
    fun uninstalledPackageIsIgnored() {
        // Ein Paket, das nicht mehr in currentFingerprints auftaucht (deinstalliert), kann per
        // Konstruktion nie im Ergebnis landen — nur currentFingerprints wird durchsucht.
        val result = SigningCertChangeDecision.evaluate(
            previousFingerprints = mapOf("com.example.gone" to "abc"),
            currentFingerprints = emptyMap(),
        )
        assertTrue(result.isEmpty())
    }
}
