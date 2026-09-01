package de.ble1st.warden.domain.sentinelbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port aus dem ConneXias-Framework-Quellprojekt
 * (`core/domain/.../sentinel/SentinelWatchdogDecisionTest.kt`) — reine JVM-Unit-Tests,
 * [SentinelWatchdogDecision] ist framework-frei (kein echtes `linkToDeath()` nötig, Zeitstempel
 * werden als Liste übergeben). */
class SentinelWatchdogDecisionTest {

    @Test
    fun noEscalationBelowThreshold() {
        assertFalse(SentinelWatchdogDecision.shouldEscalate(deathTimestampsEpochMillis = listOf(1_000L, 2_000L), nowEpochMillis = 2_000L))
    }

    @Test
    fun escalatesOnExactlyThreeDeathsWithinWindow() {
        val now = 100_000L
        val timestamps = listOf(now - 59_000, now - 30_000, now)
        assertTrue(SentinelWatchdogDecision.shouldEscalate(timestamps, now))
    }

    @Test
    fun deathsOutsideTheWindowDoNotCount() {
        val now = 100_000L
        // Zwei Tode innerhalb des Fensters, ein dritter weit davor — darf nicht mitzählen.
        val timestamps = listOf(now - 61_000, now - 30_000, now)
        assertFalse(SentinelWatchdogDecision.shouldEscalate(timestamps, now))
    }

    @Test
    fun orderOfTimestampsDoesNotMatter() {
        val now = 100_000L
        val timestamps = listOf(now, now - 59_000, now - 30_000)
        assertTrue(SentinelWatchdogDecision.shouldEscalate(timestamps, now))
    }

    @Test
    fun emptyTimestampListNeverEscalates() {
        assertFalse(SentinelWatchdogDecision.shouldEscalate(emptyList(), nowEpochMillis = 0L))
    }
}
