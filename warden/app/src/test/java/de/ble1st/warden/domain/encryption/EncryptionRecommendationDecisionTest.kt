package de.ble1st.warden.domain.encryption

import de.ble1st.warden.domain.appmanagement.ThreatSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptionRecommendationDecisionTest {

    @Test
    fun perfectStateProducesNoRecommendations() {
        val result = EncryptionRecommendationDecision.evaluate(
            storageEncrypted = true,
            keystoreSecurityLevel = KeystoreSecurityLevel.HARDWARE_BACKED,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun disabledDeviceEncryptionIsCritical() {
        val result = EncryptionRecommendationDecision.evaluate(
            storageEncrypted = false,
            keystoreSecurityLevel = KeystoreSecurityLevel.HARDWARE_BACKED,
        )
        assertEquals(1, result.size)
        assertEquals(EncryptionRecommendationType.DEVICE_ENCRYPTION_INACTIVE, result[0].type)
        assertEquals(ThreatSeverity.CRITICAL, result[0].severity)
    }

    @Test
    fun softwareOnlyKeystoreIsWarning() {
        val result = EncryptionRecommendationDecision.evaluate(
            storageEncrypted = true,
            keystoreSecurityLevel = KeystoreSecurityLevel.SOFTWARE,
        )
        assertEquals(1, result.size)
        assertEquals(EncryptionRecommendationType.KEYSTORE_SOFTWARE_ONLY, result[0].type)
        assertEquals(ThreatSeverity.WARNING, result[0].severity)
    }

    @Test
    fun unknownKeystoreLevelIsInfoNotWarning() {
        // Ein Lesefehler dieses einen Signals ist keine bestätigte Schwäche — dieselbe
        // Fail-Safe-Unterscheidung wie SecurityScoreDecisions Nicht-Bestrafung von UNKNOWN.
        val result = EncryptionRecommendationDecision.evaluate(
            storageEncrypted = true,
            keystoreSecurityLevel = KeystoreSecurityLevel.UNKNOWN,
        )
        assertEquals(1, result.size)
        assertEquals(EncryptionRecommendationType.KEYSTORE_UNKNOWN, result[0].type)
        assertEquals(ThreatSeverity.INFO, result[0].severity)
    }

    @Test
    fun bothProblemsProduceTwoIndependentRecommendations() {
        val result = EncryptionRecommendationDecision.evaluate(
            storageEncrypted = false,
            keystoreSecurityLevel = KeystoreSecurityLevel.SOFTWARE,
        )
        assertEquals(2, result.size)
        assertTrue(result.any { it.type == EncryptionRecommendationType.DEVICE_ENCRYPTION_INACTIVE })
        assertTrue(result.any { it.type == EncryptionRecommendationType.KEYSTORE_SOFTWARE_ONLY })
    }
}
