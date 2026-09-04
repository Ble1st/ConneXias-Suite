package de.ble1st.warden.domain.clipboard

import de.ble1st.warden.domain.clipboard.ClipboardAccessDecision.Action
import de.ble1st.warden.domain.clipboard.ClipboardAccessDecision.IgnoreReason
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardAccessDecisionTest {

    private fun evaluate(
        monitoringEnabled: Boolean = true,
        packageName: String? = "com.example.foreign",
        ownPackageName: String = "de.ble1st.warden",
        isPassword: Boolean = false,
        addedCount: Int = ClipboardAccessDecision.MIN_BURST_CHARS,
        text: String = "pasted text",
    ) = ClipboardAccessDecision.evaluate(monitoringEnabled, packageName, ownPackageName, isPassword, addedCount, text)

    @Test
    fun monitoringDisabledAlwaysIgnoresRegardlessOfOtherFields() {
        assertEquals(Action.Ignore(IgnoreReason.MONITORING_DISABLED), evaluate(monitoringEnabled = false))
    }

    @Test
    fun ownPackageIsIgnoredEvenWhenEverythingElseQualifies() {
        assertEquals(
            Action.Ignore(IgnoreReason.OWN_PACKAGE),
            evaluate(packageName = "de.ble1st.warden", ownPackageName = "de.ble1st.warden"),
        )
    }

    @Test
    fun missingPackageNameIsIgnored() {
        assertEquals(Action.Ignore(IgnoreReason.OWN_PACKAGE), evaluate(packageName = null))
    }

    @Test
    fun passwordFieldIsIgnoredEvenWithLargeBurst() {
        assertEquals(Action.Ignore(IgnoreReason.PASSWORD_FIELD), evaluate(isPassword = true, addedCount = 20))
    }

    @Test
    fun blankTextIsIgnored() {
        assertEquals(Action.Ignore(IgnoreReason.EMPTY_TEXT), evaluate(text = "   "))
    }

    @Test
    fun singleCharacterBurstBelowThresholdIsIgnoredAsOrdinaryTyping() {
        assertEquals(Action.Ignore(IgnoreReason.NOT_PASTE_LIKE), evaluate(addedCount = 1))
    }

    @Test
    fun burstAtOrAboveThresholdIsCapturedWithFullText() {
        assertEquals(Action.Capture("pasted text"), evaluate(addedCount = ClipboardAccessDecision.MIN_BURST_CHARS, text = "pasted text"))
    }
}
