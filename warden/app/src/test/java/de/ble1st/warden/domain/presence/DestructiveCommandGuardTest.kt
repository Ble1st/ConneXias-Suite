package de.ble1st.warden.domain.presence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Meilenstein F.4 — reine JVM-Unit-Tests. */
class DestructiveCommandGuardTest {

    @Test
    fun executionIsAllowedOnlyOnNonDebugBuilds() {
        assertTrue(DestructiveCommandGuard.isExecutionAllowed(isDebugBuild = false))
        assertFalse(DestructiveCommandGuard.isExecutionAllowed(isDebugBuild = true))
    }

    @Test
    fun dryRunIsAllowedOnlyOnTestOnlyBuilds() {
        assertTrue(DestructiveCommandGuard.isDryRunAllowed(isTestOnlyBuild = true))
        assertFalse(DestructiveCommandGuard.isDryRunAllowed(isTestOnlyBuild = false))
    }
}
