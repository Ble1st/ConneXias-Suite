package de.ble1st.warden.domain.presence

import org.junit.Assert.assertEquals
import org.junit.Test

/** Meilenstein F.3 — reine JVM-Unit-Tests. */
class SensitiveActionDecisionTest {

    private fun evaluate(
        executionAllowed: Boolean = true,
        rateLimitOk: Boolean = true,
        confirmationTextMatches: Boolean = true,
        presenceConsumed: Boolean = true,
    ) = SensitiveActionDecision.evaluate(executionAllowed, rateLimitOk, confirmationTextMatches, presenceConsumed)

    @Test
    fun allConditionsMetIsApproved() {
        assertEquals(SensitiveActionDecisionResult.Approved, evaluate())
    }

    @Test
    fun debugBuildIsBlockedRegardlessOfEverythingElse() {
        assertEquals(
            SensitiveActionDecisionResult.ExecutionBlocked,
            evaluate(executionAllowed = false, rateLimitOk = false, confirmationTextMatches = false, presenceConsumed = false),
        )
    }

    @Test
    fun rateLimitedTakesPriorityOverConfirmationAndPresence() {
        assertEquals(
            SensitiveActionDecisionResult.RateLimited,
            evaluate(rateLimitOk = false, confirmationTextMatches = false, presenceConsumed = false),
        )
    }

    @Test
    fun wrongConfirmationTextTakesPriorityOverPresence() {
        assertEquals(
            SensitiveActionDecisionResult.WrongConfirmationText,
            evaluate(confirmationTextMatches = false, presenceConsumed = false),
        )
    }

    @Test
    fun presenceNotProvenIsReportedWhenOnlyPresenceFails() {
        assertEquals(SensitiveActionDecisionResult.PresenceNotProven, evaluate(presenceConsumed = false))
    }
}
