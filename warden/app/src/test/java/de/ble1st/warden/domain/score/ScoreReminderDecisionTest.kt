package de.ble1st.warden.domain.score

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ScoreReminderDecisionTest {

    private val now = 1_000_000_000_000L

    @Test
    fun doesNotRemindWhenScoreIsRecent() {
        assertFalse(ScoreReminderDecision.shouldRemind(hasRecentScoreEntry = true, lastReminderAtMillis = null, nowMillis = now, dedupWindowDays = 7))
    }

    @Test
    fun remindsOnFirstStaleCheckWithNoPriorReminder() {
        assertTrue(ScoreReminderDecision.shouldRemind(hasRecentScoreEntry = false, lastReminderAtMillis = null, nowMillis = now, dedupWindowDays = 7))
    }

    @Test
    fun doesNotRemindAgainWithinDedupWindow() {
        val lastReminder = now - TimeUnit.DAYS.toMillis(3)
        assertFalse(ScoreReminderDecision.shouldRemind(hasRecentScoreEntry = false, lastReminderAtMillis = lastReminder, nowMillis = now, dedupWindowDays = 7))
    }

    @Test
    fun remindsAgainOnceDedupWindowHasElapsed() {
        val lastReminder = now - TimeUnit.DAYS.toMillis(8)
        assertTrue(ScoreReminderDecision.shouldRemind(hasRecentScoreEntry = false, lastReminderAtMillis = lastReminder, nowMillis = now, dedupWindowDays = 7))
    }
}
