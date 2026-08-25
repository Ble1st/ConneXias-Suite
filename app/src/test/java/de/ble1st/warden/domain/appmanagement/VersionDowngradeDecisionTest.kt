package de.ble1st.warden.domain.appmanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionDowngradeDecisionTest {

    @Test
    fun unknownPackageIsNeverFlagged() {
        // Kein Eintrag in previousVersionCodes -> keine Baseline zum Vergleichen, nur ein neuer
        // Eintrag, kein "zurückgestuft"-Fund.
        val result = VersionDowngradeDecision.evaluate(
            previousVersionCodes = emptyMap(),
            currentVersionCodes = mapOf("com.example.new" to 3L),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun sameVersionCodeIsNotFlagged() {
        val result = VersionDowngradeDecision.evaluate(
            previousVersionCodes = mapOf("com.example.app" to 5L),
            currentVersionCodes = mapOf("com.example.app" to 5L),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun normalUpdateIsNotFlagged() {
        val result = VersionDowngradeDecision.evaluate(
            previousVersionCodes = mapOf("com.example.app" to 5L),
            currentVersionCodes = mapOf("com.example.app" to 6L),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun downgradeIsFlagged() {
        val result = VersionDowngradeDecision.evaluate(
            previousVersionCodes = mapOf("com.example.app" to 10L),
            currentVersionCodes = mapOf("com.example.app" to 3L),
        )
        assertEquals(setOf("com.example.app"), result)
    }

    @Test
    fun uninstalledPackageIsIgnored() {
        val result = VersionDowngradeDecision.evaluate(
            previousVersionCodes = mapOf("com.example.gone" to 10L),
            currentVersionCodes = emptyMap(),
        )
        assertTrue(result.isEmpty())
    }
}
