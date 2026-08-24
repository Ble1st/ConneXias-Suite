package de.ble1st.warden.domain.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Meilenstein C.5 — Abnahme (Konzept 19): "Geräte-Lockdown-Bündel ... als zusammengesetzter
 * Eintrag." Reiner JVM-Unit-Test der Komposition-Logik selbst — bewusst mit Fake-Safeguards
 * statt den echten C.5-DPM-Implementierungen (`:core:data`): `DISALLOW_SAFE_BOOT`/
 * `DISALLOW_FACTORY_RESET`/`DISALLOW_DEBUGGING_FEATURES`/USB-Data-Signaling werden auf dem
 * Testgerät bewusst nie live per `apply()`/`revert()` durchgetestet (Risiko, den einzigen
 * bekannten DO-Rückbauweg bzw. die ADB-Verbindung selbst zu kappen) — die Komposition-Logik
 * braucht dafür aber ohnehin kein echtes DPM.
 */
class CompositeSafeguardTest {

    private class FakeSafeguard(
        override val id: String,
        private val failOnApply: Boolean = false,
        private val failOnRevert: Boolean = false,
    ) : Safeguard {
        private var active = false
        var applyCount = 0
            private set
        var revertCount = 0
            private set

        override fun apply() {
            applyCount++
            if (failOnApply) throw IllegalStateException("simulierter Fehler bei apply() für $id")
            active = true
        }

        override fun revert() {
            revertCount++
            if (failOnRevert) throw IllegalStateException("simulierter Fehler bei revert() für $id")
            active = false
        }

        override fun isActive(): Boolean = active
    }

    @Test
    fun applyCallsApplyOnAllMembers() {
        val a = FakeSafeguard(id = "a")
        val b = FakeSafeguard(id = "b")
        val composite = CompositeSafeguard(id = "bundle", members = listOf(a, b))

        composite.apply()

        assertEquals(1, a.applyCount)
        assertEquals(1, b.applyCount)
    }

    @Test
    fun revertCallsRevertOnAllMembers() {
        val a = FakeSafeguard(id = "a")
        val b = FakeSafeguard(id = "b")
        val composite = CompositeSafeguard(id = "bundle", members = listOf(a, b))
        composite.apply()

        composite.revert()

        assertEquals(1, a.revertCount)
        assertEquals(1, b.revertCount)
    }

    @Test
    fun isActiveIsTrueOnlyWhenAllMembersAreActive() {
        val a = FakeSafeguard(id = "a")
        val b = FakeSafeguard(id = "b")
        val composite = CompositeSafeguard(id = "bundle", members = listOf(a, b))

        a.apply()
        assertFalse("nur ein Mitglied aktiv darf nicht als 'Bündel aktiv' zählen", composite.isActive())

        b.apply()
        assertTrue(composite.isActive())
    }

    @Test
    fun applyContinuesAfterOneMemberFailsAndThrowsAtTheEnd() {
        val failing = FakeSafeguard(id = "failing", failOnApply = true)
        val healthy = FakeSafeguard(id = "healthy")
        val composite = CompositeSafeguard(id = "bundle", members = listOf(failing, healthy))

        val exception = try {
            composite.apply()
            null
        } catch (e: CompositeSafeguardException) {
            e
        }

        assertEquals(
            "das gesunde Mitglied muss trotz des Fehlers beim anderen versucht werden",
            1,
            healthy.applyCount,
        )
        assertTrue(healthy.isActive())
        requireNotNull(exception) { "apply() hätte eine CompositeSafeguardException werfen müssen" }
        assertEquals("bundle", exception.compositeId)
        assertEquals("apply", exception.operation)
        assertEquals(listOf("failing"), exception.failures.map { it.memberId })
    }

    @Test
    fun revertContinuesAfterOneMemberFailsAndThrowsAtTheEnd() {
        val failing = FakeSafeguard(id = "failing", failOnRevert = true)
        val healthy = FakeSafeguard(id = "healthy")
        val composite = CompositeSafeguard(id = "bundle", members = listOf(failing, healthy))
        composite.apply()

        val exception = try {
            composite.revert()
            null
        } catch (e: CompositeSafeguardException) {
            e
        }

        assertFalse(healthy.isActive())
        requireNotNull(exception) { "revert() hätte eine CompositeSafeguardException werfen müssen" }
        assertEquals("revert", exception.operation)
        assertEquals(listOf("failing"), exception.failures.map { it.memberId })
    }

    @Test
    fun requiresAtLeastOneMember() {
        try {
            CompositeSafeguard(id = "empty", members = emptyList())
            throw AssertionError("Konstruktor hätte bei leerer Mitgliederliste werfen müssen")
        } catch (expected: IllegalArgumentException) {
            // erwartet
        }
    }
}
