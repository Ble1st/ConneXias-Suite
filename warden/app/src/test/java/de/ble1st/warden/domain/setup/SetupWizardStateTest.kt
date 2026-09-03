package de.ble1st.warden.domain.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reiner JVM-Test — [SetupWizardState] ist bewusst framework-frei (keine `R`-Referenzen, kein
 * `Context`), die Texte liegen in `ui/SetupWizardScreen.kt`. */
class SetupWizardStateTest {

    private fun state(
        deviceOwner: Boolean = false,
        pin: Boolean = false,
        profile: Boolean = false,
        sentinel: Boolean = false,
        drill: Boolean = false,
    ) = SetupWizardState(
        isDeviceOwner = deviceOwner,
        pinConfigured = pin,
        profileApplied = profile,
        sentinelInstalled = sentinel,
        emergencyDrillConfirmed = drill,
    )

    @Test
    fun `nur Device Owner und PIN sind Pflicht`() {
        // Die Trennung ist die eigentliche Aussage des Assistenten (s. SetupStep-Doc): ein Gerät
        // ohne Kiosk-Nutzung soll nicht zur Sentinel-Installation und zum Notruf-Drill gedrängt
        // werden.
        assertEquals(
            listOf(SetupStep.DEVICE_OWNER, SetupStep.PIN),
            SetupStep.entries.filter { it.required },
        )
    }

    @Test
    fun `requiredComplete haengt nicht an den optionalen Schritten`() {
        assertTrue(state(deviceOwner = true, pin = true).requiredComplete)
        assertFalse(state(deviceOwner = true, pin = false, profile = true, sentinel = true, drill = true).requiredComplete)
        assertFalse(state(deviceOwner = false, pin = true).requiredComplete)
    }

    @Test
    fun `Notruf-Drill ist ohne Sentinel gesperrt`() {
        assertTrue(state().isBlocked(SetupStep.EMERGENCY_DRILL))
        assertFalse(state(sentinel = true).isBlocked(SetupStep.EMERGENCY_DRILL))
    }

    @Test
    fun `kein anderer Schritt ist je gesperrt`() {
        val blank = state()
        for (step in SetupStep.entries - SetupStep.EMERGENCY_DRILL) {
            assertFalse(step.name, blank.isBlocked(step))
        }
    }

    @Test
    fun `nextOpenStep folgt der Deklarationsreihenfolge`() {
        assertEquals(SetupStep.DEVICE_OWNER, state().nextOpenStep)
        assertEquals(SetupStep.PIN, state(deviceOwner = true).nextOpenStep)
        assertEquals(SetupStep.PROFILE, state(deviceOwner = true, pin = true).nextOpenStep)
    }

    @Test
    fun `nextOpenStep ueberspringt den gesperrten Drill`() {
        // Ohne Sentinel ist der Drill nicht bearbeitbar — ihn trotzdem als "nächsten Schritt" zu
        // melden würde den Nutzer auf einen Knopf schicken, den es dort nicht gibt.
        val withoutSentinel = state(deviceOwner = true, pin = true, profile = true, sentinel = false)
        assertEquals(SetupStep.SENTINEL, withoutSentinel.nextOpenStep)

        // Auch wenn Sentinel als nicht gewünscht übersprungen bliebe: der Drill darf nie als
        // nächster Schritt erscheinen, solange er gesperrt ist.
        val sentinelSkipped = state(deviceOwner = true, pin = true, profile = true, sentinel = false)
        assertFalse(SetupStep.EMERGENCY_DRILL == sentinelSkipped.nextOpenStep)
    }

    @Test
    fun `nextOpenStep ist null wenn alles erledigt ist`() {
        assertNull(state(deviceOwner = true, pin = true, profile = true, sentinel = true, drill = true).nextOpenStep)
    }

    @Test
    fun `doneCount zaehlt alle Schritte`() {
        assertEquals(0, state().doneCount)
        assertEquals(2, state(deviceOwner = true, pin = true).doneCount)
        assertEquals(
            SetupStep.entries.size,
            state(deviceOwner = true, pin = true, profile = true, sentinel = true, drill = true).doneCount,
        )
    }
}
