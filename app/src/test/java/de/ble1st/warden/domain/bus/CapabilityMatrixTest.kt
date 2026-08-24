package de.ble1st.warden.domain.bus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Meilenstein E.6 — Abnahme (Konzept 19): "Kommandoklassen pro Rolle; destruktive Kommandos
 * NICHT über den Bus erreichbar." Reduzierte Fassung des Quellprojekt-Tests (dort mehrere
 * Cross-APK-Rollen Herald/Sentinel/Barbican durchgetestet) — hier gibt es nur noch [Role.OWNER],
 * s. [Role]-Klassendoc.
 */
class CapabilityMatrixTest {

    @Test
    fun destructiveCommandsAreNeverAllowedForAnyRole() {
        for (role in Role.entries) {
            assertFalse(
                "$role darf wipeData/reboot nicht auslösen dürfen (Invariante 1)",
                CapabilityMatrix.isAllowed(role, BusCommand.DESTRUCTIVE),
            )
        }
    }

    @Test
    fun ownerMayReadAndSwitchAndAccessLog() {
        assertTrue(CapabilityMatrix.isAllowed(Role.OWNER, BusCommand.READ))
        assertTrue(CapabilityMatrix.isAllowed(Role.OWNER, BusCommand.NON_DESTRUCTIVE_SWITCH))
        assertTrue(CapabilityMatrix.isAllowed(Role.OWNER, BusCommand.LOG_ACCESS))
    }

    @Test
    fun ownerMayNeverTriggerTheDestructiveCommand() {
        assertFalse(CapabilityMatrix.isAllowed(Role.OWNER, BusCommand.DESTRUCTIVE))
    }
}
