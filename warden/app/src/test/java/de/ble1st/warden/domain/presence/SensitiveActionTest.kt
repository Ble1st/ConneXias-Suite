package de.ble1st.warden.domain.presence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WardenLock (Finalisierungsphase 2026-08-24) — `allowsSessionPresence` ist strukturell
 * (nicht nur dokumentiert) `false` für `WIPE_DATA`, s. `SensitiveAction`-Klassendoc. */
class SensitiveActionTest {

    @Test
    fun wipeDataDoesNotAllowSessionPresence() {
        assertFalse(SensitiveAction.WIPE_DATA.allowsSessionPresence)
    }

    @Test
    fun everyOtherActionAllowsSessionPresence() {
        val eligible = SensitiveAction.entries - SensitiveAction.WIPE_DATA
        eligible.forEach { action ->
            assertTrue("$action should allow session presence", action.allowsSessionPresence)
        }
    }
}
