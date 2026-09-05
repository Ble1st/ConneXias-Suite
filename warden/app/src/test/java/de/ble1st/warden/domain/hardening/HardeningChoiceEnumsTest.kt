package de.ble1st.warden.domain.hardening

import de.ble1st.warden.domain.appmanagement.FreezeMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die vier Tier-2-Auswahlmenüs (2026-09-05). Sie tragen keine Berechnung, aber sehr wohl
 * Invarianten, die stillschweigend brechen können, wenn jemand später eine Stufe ergänzt oder
 * umbenennt — genau die Art Regression, die `WardenProfileSpecTest.strengthOrderMatchesSubsetOrder`
 * für die Profile schon abfängt.
 *
 * Der wichtigste Punkt überall: **der Default ist der Zustand von vor der Einführung.** Ein
 * Versionswechsel darf auf einem bestehenden Gerät nichts einschalten, was der Nutzer nie gewählt
 * hat — bei [FailedAttemptsWipeThreshold] wäre das sogar ein unwiderruflicher Datenverlust.
 */
class HardeningChoiceEnumsTest {

    @Test
    fun `alle vier Defaults sind der Zustand vor Einfuehrung der Funktion`() {
        assertEquals(FreezeMethod.AUTOMATIK, FreezeMethod.DEFAULT)
        assertEquals(LocationEnforcement.AUS, LocationEnforcement.DEFAULT)
        assertEquals(TimeIntegrityMode.AUS, TimeIntegrityMode.DEFAULT)
        assertEquals(FailedAttemptsWipeThreshold.AUS, FailedAttemptsWipeThreshold.DEFAULT)
    }

    @Test
    fun `FreezeMethod bildet die beiden Mechanismen korrekt ab`() {
        assertTrue(FreezeMethod.NUR_VERSTECKEN.usesHide)
        assertFalse(FreezeMethod.NUR_VERSTECKEN.usesSuspend)
        assertFalse(FreezeMethod.NUR_SUSPENDIEREN.usesHide)
        assertTrue(FreezeMethod.NUR_SUSPENDIEREN.usesSuspend)
        assertTrue(FreezeMethod.BEIDES.usesHide)
        assertTrue(FreezeMethod.BEIDES.usesSuspend)
    }

    /** [FreezeMethod.AUTOMATIK] und [FreezeMethod.BEIDES] benutzen beide Mechanismen — sie
     * unterscheiden sich ausschließlich darin, ob Suspendieren ein Fallback ist. Ohne diese
     * Unterscheidung wären die zwei Stufen dasselbe. */
    @Test
    fun `Automatik und Beides trennen sich allein ueber suspendOnlyAsFallback`() {
        assertTrue(FreezeMethod.AUTOMATIK.suspendOnlyAsFallback)
        assertFalse(FreezeMethod.BEIDES.suspendOnlyAsFallback)
        assertEquals(FreezeMethod.AUTOMATIK.usesHide, FreezeMethod.BEIDES.usesHide)
        assertEquals(FreezeMethod.AUTOMATIK.usesSuspend, FreezeMethod.BEIDES.usesSuspend)
    }

    @Test
    fun `Ortung sperrt die Einstellung nur auf der staerksten Stufe, und nie ohne sie einzuschalten`() {
        LocationEnforcement.entries.forEach { mode ->
            if (mode.locksSetting) {
                assertTrue(
                    "Eine Stufe, die die Ortungs-Einstellung sperrt, muss die Ortung auch einschalten — " +
                        "sonst wäre 'aus' unumkehrbar festgenagelt: $mode",
                    mode.enablesLocation,
                )
            }
        }
        assertFalse(LocationEnforcement.AUS.enablesLocation)
        assertFalse(LocationEnforcement.AUS.locksSetting)
    }

    @Test
    fun `Zeit-Integritaet sperrt nie, ohne Netzzeit zu erzwingen`() {
        TimeIntegrityMode.entries.forEach { mode ->
            if (mode.locksSetting) assertTrue("$mode sperrt, erzwingt aber keine Netzzeit", mode.enforcesAutoTime)
        }
        assertFalse(TimeIntegrityMode.AUS.enforcesAutoTime)
        assertFalse(TimeIntegrityMode.AUS.locksSetting)
        assertTrue(TimeIntegrityMode.NUR_AUTOMATISCH.enforcesAutoTime)
        assertFalse(TimeIntegrityMode.NUR_AUTOMATISCH.locksSetting)
        assertTrue(TimeIntegrityMode.AUTOMATISCH_UND_SPERREN.locksSetting)
    }

    @Test
    fun `Loeschgrenze ist aus genau dann, wenn sie null Versuche bedeutet`() {
        FailedAttemptsWipeThreshold.entries.forEach { threshold ->
            assertEquals("$threshold", threshold.attempts > 0, threshold.isEnabled)
        }
    }

    /** Die Untergrenze ist eine bewusste Entscheidung (s. Klassendoc: unter etwa zehn Versuchen
     * sind versehentliche Auslösungen realistisch, und diese Aktion hat keinen Rückweg). Ein
     * späteres "nur mal schnell 5 hinzufügen" soll hier auffallen. */
    @Test
    fun `keine aktive Stufe liegt unter zehn Fehlversuchen`() {
        FailedAttemptsWipeThreshold.entries.filter { it.isEnabled }.forEach {
            assertTrue("$it liegt unter der bewusst gewählten Untergrenze", it.attempts >= 10)
        }
    }

    @Test
    fun `fromAttempts findet jede angebotene Stufe zurueck`() {
        FailedAttemptsWipeThreshold.entries.forEach {
            assertEquals(it, FailedAttemptsWipeThreshold.fromAttempts(it.attempts))
        }
    }

    /** Ein vom System oder einem anderen Admin gesetzter Wert, der zu keiner Stufe passt, ergibt
     * `null` — die UI sagt dann "vom System gesetzt", statt eine gerundete Zahl zu behaupten. */
    @Test
    fun `fromAttempts rundet einen fremden Wert nicht auf eine angebotene Stufe`() {
        assertNull(FailedAttemptsWipeThreshold.fromAttempts(7))
        assertNull(FailedAttemptsWipeThreshold.fromAttempts(11))
        assertNull(FailedAttemptsWipeThreshold.fromAttempts(1000))
    }

    /** Keine Stufe darf beide Mechanismen abschalten — das wäre ein "Einfrieren", das nichts tut. */
    @Test
    fun `jede FreezeMethod benutzt mindestens einen Mechanismus`() {
        FreezeMethod.entries.forEach {
            assertTrue("$it friert mit keinem Mechanismus ein", it.usesHide || it.usesSuspend)
        }
    }
}
