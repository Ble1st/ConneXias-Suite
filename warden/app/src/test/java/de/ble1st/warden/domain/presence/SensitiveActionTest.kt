package de.ble1st.warden.domain.presence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WardenLock (Finalisierungsphase 2026-08-24) — `allowsSessionPresence` ist strukturell
 * (nicht nur dokumentiert) `false` für die Aktionen ohne Rückweg, s. `SensitiveAction`-Klassendoc.
 *
 * Seit 2026-09-05 sind das **zwei**: `WIPE_DATA` und `TRANSFER_OWNERSHIP`. Beide haben gemeinsam,
 * dass Warden danach nichts mehr rückgängig machen kann — beim einen sind die Daten weg, beim
 * anderen die Rechte. Für beide ist ein möglicherweise stundenalter App-Eintritts-Nachweis zu
 * wenig; ein frischer Biometrie-/PIN-Nachweis bleibt Pflicht. */
class SensitiveActionTest {

    /** Die Aktionen, für die ein bereits erbrachter Sitzungsnachweis bewusst *nicht* genügt. */
    private val noSessionPresence = setOf(SensitiveAction.WIPE_DATA, SensitiveAction.TRANSFER_OWNERSHIP)

    @Test
    fun actionsWithoutWayBackDoNotAllowSessionPresence() {
        noSessionPresence.forEach { action ->
            assertFalse("$action must not reuse the app-entry proof", action.allowsSessionPresence)
        }
    }

    @Test
    fun everyOtherActionAllowsSessionPresence() {
        val eligible = SensitiveAction.entries - noSessionPresence
        eligible.forEach { action ->
            assertTrue("$action should allow session presence", action.allowsSessionPresence)
        }
    }

    /** Jede Aktion braucht einen eigenen Bestätigungstext — zwei gleiche würden bedeuten, dass ein
     * für die eine getippter Text auch die andere freischaltet. */
    @Test
    fun everyActionHasItsOwnConfirmationPhrase() {
        val phrases = SensitiveAction.entries.map { it.confirmationPhrase }
        assertTrue("Bestätigungstexte sind nicht eindeutig: $phrases", phrases.size == phrases.toSet().size)
    }
}
