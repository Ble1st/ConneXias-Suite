package de.ble1st.warden.domain.pin

import de.ble1st.warden.domain.appmanagement.ThreatSeverity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WardenLockTaskAutoEngageDecisionTest {

    @Test
    fun requiresAllFourConditions() {
        assertTrue(
            WardenLockTaskAutoEngageDecision.shouldRequestEngage(
                severity = ThreatSeverity.CRITICAL,
                drillConfirmed = true,
                lockdownArmed = true,
                autoEngageEnabled = true,
            ),
        )
    }

    @Test
    fun warningSeverityNeverEngages() {
        assertFalse(
            WardenLockTaskAutoEngageDecision.shouldRequestEngage(
                severity = ThreatSeverity.WARNING,
                drillConfirmed = true,
                lockdownArmed = true,
                autoEngageEnabled = true,
            ),
        )
    }

    @Test
    fun missingDrillConfirmationBlocks() {
        assertFalse(
            WardenLockTaskAutoEngageDecision.shouldRequestEngage(
                severity = ThreatSeverity.CRITICAL,
                drillConfirmed = false,
                lockdownArmed = true,
                autoEngageEnabled = true,
            ),
        )
    }

    @Test
    fun missingLockdownArmedBlocks() {
        assertFalse(
            WardenLockTaskAutoEngageDecision.shouldRequestEngage(
                severity = ThreatSeverity.CRITICAL,
                drillConfirmed = true,
                lockdownArmed = false,
                autoEngageEnabled = true,
            ),
        )
    }

    @Test
    fun missingAutoEngageOptInBlocks() {
        assertFalse(
            WardenLockTaskAutoEngageDecision.shouldRequestEngage(
                severity = ThreatSeverity.CRITICAL,
                drillConfirmed = true,
                lockdownArmed = true,
                autoEngageEnabled = false,
            ),
        )
    }
}
