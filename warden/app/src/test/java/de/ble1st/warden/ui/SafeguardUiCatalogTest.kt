package de.ble1st.warden.ui

import de.ble1st.warden.domain.profile.WardenProfile
import de.ble1st.warden.domain.profile.WardenProfileSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vorschlag U-1 (2026-08-29). Der Zweck der Umstellung war, dass ein neuer Safeguard nicht mehr an
 * drei Stellen gepflegt werden muss — dieser Test macht daraus eine *strukturelle* Zusicherung
 * statt einer Absichtserklärung: er schlägt fehl, sobald ein Schalter in einem Profil steht, aber
 * keine Zeile in der UI hat.
 *
 * Genau dieser Fehler ist historisch passiert (`SentinelUninstallProtectionSafeguard` war ab
 * 2026-08-26 im Registry-Katalog, bekam die UI-Zeile aber erst am 2026-08-27) — er wäre hier
 * aufgefallen.
 *
 * Reiner JVM-Test ohne Android: [SafeguardUiCatalog] referenziert die Safeguard-IDs über
 * `const val`s, die der Compiler einsetzt — die `Context`-bedürftigen Safeguard-Klassen selbst
 * werden dabei nie geladen.
 */
class SafeguardUiCatalogTest {

    @Test
    fun everySafeguardInAProfileHasAUiRow() {
        val uiIds = SafeguardUiCatalog.allEntries.map { it.id }.toSet()
        for (profile in WardenProfile.entries) {
            val missing = WardenProfileSpec.idsOn(profile) - uiIds
            assertTrue(
                "Profil ${profile.label} enthält Safeguards ohne UI-Zeile in SafeguardUiCatalog: $missing",
                missing.isEmpty(),
            )
        }
    }

    @Test
    fun entryIdsAreUnique() {
        val ids = SafeguardUiCatalog.allEntries.map { it.id }
        assertEquals("Doppelte IDs im UI-Katalog: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}", ids.size, ids.toSet().size)
    }

    @Test
    fun groupIdsAreUnique() {
        val ids = SafeguardUiCatalog.groups.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyGroupHasEntries() {
        SafeguardUiCatalog.groups.forEach { group ->
            assertTrue("Gruppe ${group.id} ist leer", group.entries.isNotEmpty())
        }
    }

    /** Der abgeleitete Profilbezug (Vorschlag U-3) muss mit [WardenProfileSpec] übereinstimmen —
     * sonst zeigt die UI eine Zugehörigkeit an, die beim nächsten Profil-Apply nicht eintritt. */
    @Test
    fun profilesContainingMatchesTheProfileSpec() {
        for (entry in SafeguardUiCatalog.allEntries) {
            if (entry.id == SafeguardUiCatalog.USB_AUTO_LOCK_ID) continue
            val expected = WardenProfile.entries.filter { entry.id in WardenProfileSpec.idsOn(it) }.toSet()
            assertEquals("Profilbezug für ${entry.id}", expected, SafeguardUiCatalog.profilesContaining(entry.id))
        }
    }

    /** USB-Auto-Lock ist kein Registry-Safeguard, wird von `WardenProfileApplier` aber für jedes
     * Profil gesetzt — die UI muss das zeigen, sonst wirkt der Schalter fälschlich profillos. */
    @Test
    fun usbAutoLockCountsAsPartOfEveryProfile() {
        assertEquals(
            WardenProfile.entries.toSet(),
            SafeguardUiCatalog.profilesContaining(SafeguardUiCatalog.USB_AUTO_LOCK_ID),
        )
    }

    /** Die Suche muss auch den Beschreibungstext treffen: "IMSI" steht nur dort, nicht im Titel
     * des 2G-Schalters — genau der Fall, für den der Filter gedacht ist. */
    @Test
    fun searchAlsoMatchesTheSupportingText() {
        val hits = SafeguardUiCatalog.allEntries.filter { it.matches("IMSI") }
        assertEquals(1, hits.size)
        assertTrue(hits.single().label.contains("2G"))
    }

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(SafeguardUiCatalog.allEntries.all { it.matches("") })
        assertTrue(SafeguardUiCatalog.allEntries.all { it.matches("   ") })
    }

    @Test
    fun searchIsCaseInsensitive() {
        assertTrue(SafeguardUiCatalog.allEntries.any { it.matches("kamera") })
        assertTrue(SafeguardUiCatalog.allEntries.any { it.matches("KAMERA") })
    }

    @Test
    fun nonsenseQueryMatchesNothing() {
        assertFalse(SafeguardUiCatalog.allEntries.any { it.matches("zzzgibtesnicht") })
    }

    /** Bestätigungsdialoge beim *Ein*schalten brauchen eigene Texte — der generische
     * Reset-Schutz-Text der Gegenrichtung passt dort inhaltlich nicht. */
    @Test
    fun enablingRiskEntriesCarryTheirOwnConfirmTexts() {
        SafeguardUiCatalog.allEntries
            .filter { it.riskSide == SafeguardUiCatalog.RiskSide.ENABLING }
            .forEach { entry ->
                assertTrue("confirmTitle fehlt für ${entry.id}", !entry.confirmTitle.isNullOrBlank())
                assertTrue("confirmText fehlt für ${entry.id}", !entry.confirmText.isNullOrBlank())
            }
    }
}
