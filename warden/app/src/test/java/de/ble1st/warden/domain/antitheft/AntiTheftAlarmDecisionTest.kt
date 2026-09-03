package de.ble1st.warden.domain.antitheft

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiTheftAlarmDecisionTest {

    private val enabledMotion = AntiTheftConfig(motionAlarmEnabled = true)
    private val enabledCharger = AntiTheftConfig(chargerAlarmEnabled = true)

    @Test
    fun motionBelowThresholdDoesNotTrigger() {
        assertFalse(AntiTheftAlarmDecision.shouldTriggerOnMotion(enabledMotion, isLocked = true, magnitude = 1.0f, thresholdMs2 = 4.0f))
    }

    @Test
    fun motionAboveThresholdTriggersWhenLockedAndEnabled() {
        assertTrue(AntiTheftAlarmDecision.shouldTriggerOnMotion(enabledMotion, isLocked = true, magnitude = 5.0f, thresholdMs2 = 4.0f))
    }

    @Test
    fun motionAboveThresholdDoesNotTriggerWhenUnlocked() {
        assertFalse(AntiTheftAlarmDecision.shouldTriggerOnMotion(enabledMotion, isLocked = false, magnitude = 5.0f, thresholdMs2 = 4.0f))
    }

    @Test
    fun motionAboveThresholdDoesNotTriggerWhenDisabled() {
        assertFalse(AntiTheftAlarmDecision.shouldTriggerOnMotion(AntiTheftConfig.DISABLED, isLocked = true, magnitude = 5.0f, thresholdMs2 = 4.0f))
    }

    @Test
    fun chargerDisconnectTriggersWhenLockedAndEnabled() {
        assertTrue(AntiTheftAlarmDecision.shouldTriggerOnChargerDisconnect(enabledCharger, isLocked = true))
    }

    @Test
    fun chargerDisconnectDoesNotTriggerWhenUnlocked() {
        assertFalse(AntiTheftAlarmDecision.shouldTriggerOnChargerDisconnect(enabledCharger, isLocked = false))
    }

    @Test
    fun chargerDisconnectDoesNotTriggerWhenDisabled() {
        assertFalse(AntiTheftAlarmDecision.shouldTriggerOnChargerDisconnect(AntiTheftConfig.DISABLED, isLocked = true))
    }
}
