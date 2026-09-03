package de.ble1st.warden.domain.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardClearDecisionTest {

    @Test
    fun disabledAlwaysSkipsRegardlessOfContent() {
        assertEquals(
            ClipboardClearDecision.Action.Skip(ClipboardClearDecision.SkipReason.DISABLED),
            ClipboardClearDecision.action(
                enabled = false,
                hasContent = true,
                ageMillis = 0L,
                thresholdMillis = 0L,
            ),
        )
    }

    @Test
    fun emptyClipboardIsSkippedEvenWhenEnabled() {
        assertEquals(
            ClipboardClearDecision.Action.Skip(ClipboardClearDecision.SkipReason.EMPTY),
            ClipboardClearDecision.action(
                enabled = true,
                hasContent = false,
                ageMillis = 0L,
                thresholdMillis = 0L,
            ),
        )
    }

    @Test
    fun zeroThresholdClearsImmediatelyIgnoringAge() {
        assertEquals(
            ClipboardClearDecision.Action.Clear,
            ClipboardClearDecision.action(
                enabled = true,
                hasContent = true,
                ageMillis = null,
                thresholdMillis = 0L,
            ),
        )
    }

    @Test
    fun unknownAgeWithPositiveThresholdSkipsFailClosedTowardsKeepingContent() {
        assertEquals(
            ClipboardClearDecision.Action.Skip(ClipboardClearDecision.SkipReason.AGE_UNKNOWN_KEEP),
            ClipboardClearDecision.action(
                enabled = true,
                hasContent = true,
                ageMillis = null,
                thresholdMillis = 60_000L,
            ),
        )
    }

    @Test
    fun ageBelowThresholdIsSkipped() {
        assertEquals(
            ClipboardClearDecision.Action.Skip(ClipboardClearDecision.SkipReason.BELOW_THRESHOLD),
            ClipboardClearDecision.action(
                enabled = true,
                hasContent = true,
                ageMillis = 30_000L,
                thresholdMillis = 60_000L,
            ),
        )
    }

    @Test
    fun ageAtOrAboveThresholdClears() {
        assertEquals(
            ClipboardClearDecision.Action.Clear,
            ClipboardClearDecision.action(
                enabled = true,
                hasContent = true,
                ageMillis = 60_000L,
                thresholdMillis = 60_000L,
            ),
        )
        assertEquals(
            ClipboardClearDecision.Action.Clear,
            ClipboardClearDecision.action(
                enabled = true,
                hasContent = true,
                ageMillis = 120_000L,
                thresholdMillis = 60_000L,
            ),
        )
    }
}
