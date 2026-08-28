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
            AutoProfileDecision.evaluate(nightMaximal, 23 * 60, false, lastAutoApplied = null),
        )
    }

    @Test
    fun appliesDayProfileOutsideWindow() {
        assertEquals(
            WardenProfile.ALLTAG,
            AutoProfileDecision.evaluate(nightMaximal, 12 * 60, false, lastAutoApplied = WardenProfile.MAXIMAL),
        )
    }

    /** Kein erneutes Anwenden desselben Profils bei jedem 15-Minuten-Lauf. */
    @Test
    fun doesNothingWhenTargetAlreadyApplied() {
        assertNull(AutoProfileDecision.evaluate(nightMaximal, 23 * 60, false, lastAutoApplied = WardenProfile.MAXIMAL))
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
            ),
        )
    }

    @Test
    fun criticalFindingIsIgnoredWhenEscalationIsOff() {
        assertEquals(
            WardenProfile.ALLTAG,
            AutoProfileDecision.evaluate(nightMaximal, 12 * 60, criticalFindingPresent = true, lastAutoApplied = null),
        )
    }

    @Test
    fun doesNothingWhenNoProfileConfiguredForCurrentSection() {
        val nightOnly = nightMaximal.copy(dayProfile = null)
        assertNull(AutoProfileDecision.evaluate(nightOnly, 12 * 60, false, lastAutoApplied = WardenProfile.MAXIMAL))
    }
}
