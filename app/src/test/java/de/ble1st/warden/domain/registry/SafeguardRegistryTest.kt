package de.ble1st.warden.domain.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Meilenstein C.1 — Abnahme (Konzept 19): "Registry-Kern: Interface apply/revert/isActive,
 * In-Memory-Verwaltung, Unit-Tests." Reiner JVM-Unit-Test — [SafeguardRegistry] ist
 * framework-frei, kein Android/DPM nötig (das kommt erst mit den konkreten Implementierungen
 * in Meilenstein C.2, `:core:data`).
 */
class SafeguardRegistryTest {

    /** Testdouble: hält eigenen Ist-Zustand + Aufrufzähler, kann auf Wunsch bei apply()/revert()
     * werfen — simuliert eine fehlschlagende zugrundeliegende (spätere DPM-)Operation. */
    private class FakeSafeguard(
        override val id: String,
        private var active: Boolean = false,
        private val failOnApply: Boolean = false,
        private val failOnRevert: Boolean = false,
    ) : Safeguard {
        var applyCount = 0
            private set
        var revertCount = 0
            private set

        override fun apply() {
            applyCount++
            if (failOnApply) throw IllegalStateException("simulierter Fehler bei apply()")
            active = true
        }

        override fun revert() {
            revertCount++
            if (failOnRevert) throw IllegalStateException("simulierter Fehler bei revert()")
            active = false
        }

        override fun isActive(): Boolean = active
    }

    @Test
    fun applyInvokesSafeguardAndTracksDesiredState() {
        val registry = SafeguardRegistry()
        val safeguard = FakeSafeguard(id = "camera_disabled")
        registry.register(safeguard)

        registry.apply("camera_disabled")

        assertEquals(1, safeguard.applyCount)
        assertTrue(registry.isActive("camera_disabled"))
        assertEquals(true, registry.desiredState("camera_disabled"))
    }

    @Test
    fun revertInvokesSafeguardAndTracksDesiredState() {
        val registry = SafeguardRegistry()
        val safeguard = FakeSafeguard(id = "camera_disabled", active = true)
        registry.register(safeguard)

        registry.revert("camera_disabled")

        assertEquals(1, safeguard.revertCount)
        assertFalse(registry.isActive("camera_disabled"))
        assertEquals(false, registry.desiredState("camera_disabled"))
    }

    @Test
    fun applyIsCalledThroughEveryTimeRegardlessOfCurrentState() {
        val registry = SafeguardRegistry()
        val safeguard = FakeSafeguard(id = "camera_disabled")
        registry.register(safeguard)

        registry.apply("camera_disabled")
        registry.apply("camera_disabled")

        // Die Registry selbst kürzt nicht ab — Idempotenz ist Vertrag des Safeguards (s.
        // Interface-Doc), nicht der Registry. Wichtig für die künftige Reconciliation (C.4).
        assertEquals(2, safeguard.applyCount)
        assertTrue(registry.isActive("camera_disabled"))
    }

    @Test
    fun desiredStateIsNullBeforeFirstApplyOrRevert() {
        val registry = SafeguardRegistry()
        registry.register(FakeSafeguard(id = "camera_disabled"))

        assertNull(registry.desiredState("camera_disabled"))
    }

    @Test
    fun isActiveReflectsLiveStateNotACachedFlag() {
        val registry = SafeguardRegistry()
        val safeguard = FakeSafeguard(id = "camera_disabled", active = true)
        registry.register(safeguard)
        assertTrue(registry.isActive("camera_disabled"))

        // Ist-Zustand ändert sich "von außen" (z. B. hat später die reale DPM-Restriction
        // jemand anders entfernt) — nicht über die Registry. isActive() muss trotzdem den
        // echten, aktuellen Zustand liefern (Konzept Abschnitt 3), der Soll-Zustand bleibt davon
        // unberührt, weil ihn niemand über apply()/revert() geändert hat.
        safeguard.revert()

        assertFalse(registry.isActive("camera_disabled"))
        assertNull(registry.desiredState("camera_disabled"))
    }

    @Test
    fun failedApplyPropagatesAndDoesNotUpdateDesiredState() {
        val registry = SafeguardRegistry()
        registry.register(FakeSafeguard(id = "camera_disabled", failOnApply = true))

        try {
            registry.apply("camera_disabled")
            throw AssertionError("apply() hätte den simulierten Fehler weiterwerfen müssen")
        } catch (expected: IllegalStateException) {
            // erwartet
        }

        assertNull(
            "ein fehlgeschlagener apply()-Aufruf darf den Soll-Zustand nicht auf true setzen",
            registry.desiredState("camera_disabled"),
        )
    }

    @Test
    fun failedRevertPropagatesAndKeepsPreviousDesiredState() {
        val registry = SafeguardRegistry()
        registry.register(FakeSafeguard(id = "camera_disabled", active = true, failOnRevert = true))
        registry.apply("camera_disabled") // hebt den Soll-Zustand zunächst korrekt auf true

        try {
            registry.revert("camera_disabled")
            throw AssertionError("revert() hätte den simulierten Fehler weiterwerfen müssen")
        } catch (expected: IllegalStateException) {
            // erwartet
        }

        assertEquals(
            "ein fehlgeschlagener revert()-Aufruf darf den zuvor bekannten Soll-Zustand nicht verwerfen",
            true,
            registry.desiredState("camera_disabled"),
        )
    }

    @Test
    fun registeringDuplicateIdThrows() {
        val registry = SafeguardRegistry()
        registry.register(FakeSafeguard(id = "camera_disabled"))

        try {
            registry.register(FakeSafeguard(id = "camera_disabled"))
            throw AssertionError("register() hätte bei doppelter id werfen müssen")
        } catch (expected: IllegalArgumentException) {
            // erwartet
        }
    }

    @Test
    fun operatingOnUnknownIdThrows() {
        val registry = SafeguardRegistry()

        try {
            registry.apply("unbekannt")
            throw AssertionError("apply() hätte bei unbekannter id werfen müssen")
        } catch (expected: NoSuchElementException) {
            // erwartet
        }
    }

    @Test
    fun registeredIdsReflectsAllRegisteredSafeguards() {
        val registry = SafeguardRegistry()
        registry.register(FakeSafeguard(id = "camera_disabled"))
        registry.register(FakeSafeguard(id = "screen_capture_disabled"))

        assertEquals(setOf("camera_disabled", "screen_capture_disabled"), registry.registeredIds())
    }

    @Test
    fun desiredStateSnapshotReflectsOnlyTouchedEntries() {
        val registry = SafeguardRegistry()
        registry.register(FakeSafeguard(id = "camera_disabled"))
        registry.register(FakeSafeguard(id = "screen_capture_disabled"))

        registry.apply("camera_disabled")

        assertEquals(mapOf("camera_disabled" to true), registry.desiredStateSnapshot())
    }

    @Test
    fun restoreDesiredStateAppliesPersistedValuesWithoutCallingTheSafeguard() {
        val registry = SafeguardRegistry()
        val safeguard = FakeSafeguard(id = "camera_disabled")
        registry.register(safeguard)

        registry.restoreDesiredState(mapOf("camera_disabled" to true))

        assertEquals(true, registry.desiredState("camera_disabled"))
        assertEquals(
            "restoreDesiredState() darf den Safeguard selbst nicht aufrufen — das ist Aufgabe der künftigen Reconciliation (C.4)",
            0,
            safeguard.applyCount,
        )
        assertFalse("Ist-Zustand bleibt unverändert, nur der gemerkte Soll-Zustand ändert sich", safeguard.isActive())
    }

    @Test
    fun restoreDesiredStateIgnoresEntriesForUnregisteredIds() {
        val registry = SafeguardRegistry()
        registry.register(FakeSafeguard(id = "camera_disabled"))

        registry.restoreDesiredState(mapOf("camera_disabled" to true, "verwaist" to false))

        assertEquals(setOf("camera_disabled"), registry.registeredIds())
        assertEquals(true, registry.desiredState("camera_disabled"))
    }

    @Test
    fun desiredStateSnapshotAfterRestoreRoundTrips() {
        val registry = SafeguardRegistry()
        registry.register(FakeSafeguard(id = "camera_disabled"))
        registry.register(FakeSafeguard(id = "screen_capture_disabled"))
        val persisted = mapOf("camera_disabled" to true, "screen_capture_disabled" to false)

        registry.restoreDesiredState(persisted)

        assertEquals(persisted, registry.desiredStateSnapshot())
    }
}
