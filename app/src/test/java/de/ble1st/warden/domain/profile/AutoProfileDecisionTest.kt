package de.ble1st.warden.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoProfileDecisionTest {

    private val nightMaximal = AutoProfileConfig(
        nightProfile = WardenProfile.MAXIMAL,
        dayProfile = WardenProfile.ALLTAG,
        nightStartMinuteOfDay = AutoProfileConfig.DEFAULT_NIGHT_START_MINUTE,
        nightEndMinuteOfDay = AutoProfileConfig.DEFAULT_NIGHT_END_MINUTE,
        escalateOnCriticalThreat = false,
    )

    @Test
    fun nightWindowWrapsAroundMidnight() {
        assertTrue(AutoProfileDecision.isWithinNightWindow(22 * 60, 6 * 60, 23 * 60))
        assertTrue(AutoProfileDecision.isWithinNightWindow(22 * 60, 6 * 60, 2 * 60))
        assertFalse(AutoProfileDecision.isWithinNightWindow(22 * 60, 6 * 60, 12 * 60))
        assertTrue(AutoProfileDecision.isWithinNightWindow(22 * 60, 6 * 60, 22 * 60))
        assertFalse(AutoProfileDecision.isWithinNightWindow(22 * 60, 6 * 60, 6 * 60))
    }

    /** Start == Ende heißt "kein Fenster", nicht "immer". */
    @Test
    fun emptyWindowIsNeverInside() {
        assertFalse(AutoProfileDecision.isWithinNightWindow(60, 60, 60))
    }

    @Test
    fun appliesNightProfileInsideWindow() {
        assertEquals(
            WardenProfile.MAXIMAL,
            AutoProfileDecision.evaluate(
                nightMaximal,
                23 * 60,
                false,
                lastAutoApplied = null,
                effectiveProfile = null,
            ),
        )
    }

    @Test
    fun appliesDayProfileOutsideWindow() {
        assertEquals(
            WardenProfile.ALLTAG,
            AutoProfileDecision.evaluate(
                nightMaximal,
                12 * 60,
                false,
                lastAutoApplied = WardenProfile.MAXIMAL,
                effectiveProfile = WardenProfile.MAXIMAL,
            ),
        )
    }

    /** Kein erneutes Anwenden desselben Profils bei jedem 15-Minuten-Lauf. */
    @Test
    fun doesNothingWhenTargetAlreadyApplied() {
        assertNull(
            AutoProfileDecision.evaluate(
                nightMaximal,
                23 * 60,
                false,
                lastAutoApplied = WardenProfile.MAXIMAL,
                effectiveProfile = WardenProfile.MAXIMAL,
            ),
        )
    }

    @Test
    fun criticalFindingBeatsSchedule() {
        assertEquals(
            WardenProfile.MAXIMAL,
            AutoProfileDecision.evaluate(
                nightMaximal.copy(escalateOnCriticalThreat = true),
                minuteOfDay = 12 * 60,
                criticalFindingPresent = true,
                lastAutoApplied = WardenProfile.ALLTAG,
                effectiveProfile = WardenProfile.ALLTAG,
            ),
        )
    }

    @Test
    fun criticalFindingIsIgnoredWhenEscalationIsOff() {
        assertEquals(
            WardenProfile.ALLTAG,
            AutoProfileDecision.evaluate(
                nightMaximal,
                12 * 60,
                criticalFindingPresent = true,
                lastAutoApplied = null,
                effectiveProfile = null,
            ),
        )
    }

    @Test
    fun doesNothingWhenNoProfileConfiguredForCurrentSection() {
        val nightOnly = nightMaximal.copy(dayProfile = null)
        assertNull(
            AutoProfileDecision.evaluate(
                nightOnly,
                12 * 60,
                false,
                lastAutoApplied = WardenProfile.MAXIMAL,
                effectiveProfile = WardenProfile.MAXIMAL,
            ),
        )
    }

    // ---- Befund Q-1 (2026-08-28): die Automatik darf nur zurücknehmen, was sie selbst gesetzt hat.

    /**
     * Der eigentliche Fund: zuletzt automatisch Alltag, mittags von Hand auf Maximal verschärft,
     * um 22:00 will der Zeitplan auf sein Nachtprofil — vorher wurde Maximal dabei
     * heruntergeschaltet.
     */
    @Test
    fun doesNotWeakenAManualHardening() {
        assertNull(
            AutoProfileDecision.evaluate(
                nightMaximal.copy(nightProfile = WardenProfile.REISE),
                minuteOfDay = 23 * 60,
                criticalFindingPresent = false,
                lastAutoApplied = WardenProfile.ALLTAG,
                effectiveProfile = WardenProfile.MAXIMAL,
            ),
        )
    }

    /** Derselbe Fall am Morgen: der Zeitplan will auf Alltag, es wirkt eine manuelle Härtung. */
    @Test
    fun doesNotWeakenAManualHardeningWhenLeavingTheNightWindow() {
        assertNull(
            AutoProfileDecision.evaluate(
                nightMaximal,
                minuteOfDay = 12 * 60,
                criticalFindingPresent = false,
                lastAutoApplied = WardenProfile.REISE,
                effectiveProfile = WardenProfile.MAXIMAL,
            ),
        )
    }

    /** Der reguläre Zeitplan bleibt unberührt: das wirkende Maximal stammt von der Automatik
     * selbst. */
    @Test
    fun stillDeEscalatesItsOwnScheduledProfile() {
        assertEquals(
            WardenProfile.ALLTAG,
            AutoProfileDecision.evaluate(
                nightMaximal,
                minuteOfDay = 12 * 60,
                criticalFindingPresent = false,
                lastAutoApplied = WardenProfile.MAXIMAL,
                effectiveProfile = WardenProfile.MAXIMAL,
            ),
        )
    }

    /** Verschärfen ist die sichere Richtung und deshalb immer erlaubt — auch über eine manuelle
     * Lockerung hinweg. */
    @Test
    fun escalationOverridesAManualProfileRegardless() {
        assertEquals(
            WardenProfile.MAXIMAL,
            AutoProfileDecision.evaluate(
                nightMaximal.copy(escalateOnCriticalThreat = true),
                minuteOfDay = 12 * 60,
                criticalFindingPresent = true,
                lastAutoApplied = WardenProfile.REISE,
                effectiveProfile = WardenProfile.ALLTAG,
            ),
        )
    }

    /** Hat die Besitzerin bewusst *abgeschwächt*, zieht die Automatik das nicht wieder hoch —
     * dafür sorgt weiterhin der Vergleich gegen `lastAutoApplied`. */
    @Test
    fun doesNotReassertItsOwnProfileAfterAManualRelaxation() {
        assertNull(
            AutoProfileDecision.evaluate(
                nightMaximal,
                minuteOfDay = 23 * 60,
                criticalFindingPresent = false,
                lastAutoApplied = WardenProfile.MAXIMAL,
                effectiveProfile = WardenProfile.ALLTAG,
            ),
        )
    }

    /** Ohne je angewendetes Profil gibt es keine fremde Härtung, die geschützt werden müsste. */
    @Test
    fun unknownEffectiveProfileDoesNotBlockTheFirstRun() {
        assertEquals(
            WardenProfile.ALLTAG,
            AutoProfileDecision.evaluate(
                nightMaximal,
                minuteOfDay = 12 * 60,
                criticalFindingPresent = false,
                lastAutoApplied = WardenProfile.MAXIMAL,
                effectiveProfile = null,
            ),
        )
    }
}
