package de.ble1st.warden.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * analyse.md (2. Durchgang, Hoch — "Profil-Apply nimmt Sentinel-Deinstallationsschutz zurück"):
 * Regressionsschutz für den Fund, dass `sentinel_uninstall_protection` (registriert im reversiblen
 * Katalog, aber in keinem [WardenProfile]) bei jedem Profil-Apply wieder abgeschaltet wurde. Prüft
 * [WardenProfileApplyDecision] statt [de.ble1st.warden.registry.WardenProfileApplier] selbst, weil
 * Letzterer einen echten `Context` braucht (s. [WardenProfileApplyDecision]-Klassendoc).
 */
class WardenProfileApplyDecisionTest {

    @Test
    fun sentinelUninstallProtectionIsNeverTouchedByAnyProfile() {
        for (profile in WardenProfile.entries) {
            val wantOn = WardenProfileSpec.idsOn(profile)
            assertTrue(
                "$profile darf sentinel_uninstall_protection nicht in seiner ID-Menge führen",
                SafeguardIds.SENTINEL_UNINSTALL_PROTECTION !in wantOn,
            )
            assertEquals(
                "$profile-Apply muss sentinel_uninstall_protection unangetastet lassen",
                WardenProfileApplyAction.LEAVE_UNTOUCHED,
                WardenProfileApplyDecision.actionFor(SafeguardIds.SENTINEL_UNINSTALL_PROTECTION, wantOn),
            )
        }
    }

    @Test
    fun idsInProfileAreApplied() {
        val wantOn = setOf("camera_disabled")
        assertEquals(WardenProfileApplyAction.APPLY, WardenProfileApplyDecision.actionFor("camera_disabled", wantOn))
    }

    @Test
    fun idsNotInProfileAndNotExemptAreReverted() {
        val wantOn = setOf("camera_disabled")
        assertEquals(WardenProfileApplyAction.REVERT, WardenProfileApplyDecision.actionFor("screen_capture_disabled", wantOn))
    }

    /**
     * analyse.md (2. Durchgang, Hoch — "Profile knacken Presence-Lockdown über geteilten
     * DPM-Zustand"): `debugging_features_disabled` steht in keinem Profil — ohne diese
     * Ausnahmebehandlung würde JEDER Profil-Apply es zurücknehmen, sobald das presence-gated
     * Lockdown-Bündel scharf ist.
     */
    @Test
    fun lockdownSharedIdsAreLeftUntouchedWhileBundleArmed() {
        for (profile in WardenProfile.entries) {
            val wantOn = WardenProfileSpec.idsOn(profile)
            for (id in WardenProfileApplyDecision.LOCKDOWN_SHARED_IDS) {
                if (id in wantOn) continue // Profil will die ID ohnehin selbst an — APPLY ist korrekt.
                assertEquals(
                    "$profile darf $id nicht revertieren, solange das Lockdown-Bündel armed ist",
                    WardenProfileApplyAction.LEAVE_UNTOUCHED,
                    WardenProfileApplyDecision.actionFor(id, wantOn, lockdownActive = true),
                )
            }
        }
    }

    @Test
    fun lockdownSharedIdsRevertNormallyWhileBundleNotArmed() {
        assertEquals(
            WardenProfileApplyAction.REVERT,
            WardenProfileApplyDecision.actionFor(
                SafeguardIds.DEBUGGING_FEATURES_DISABLED,
                WardenProfileSpec.idsOn(WardenProfile.MAXIMAL),
                lockdownActive = false,
            ),
        )
    }
}
