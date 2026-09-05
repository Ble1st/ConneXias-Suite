package de.ble1st.warden.domain.pin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ablaufdatum für vorgemerkte Scharfschalt-Anforderungen (2026-09-05) — s.
 * [PendingEngageFreshness]-Klassendoc für den im Gerätetest gefundenen Auslöser. */
class PendingEngageFreshnessTest {

    private val window = PendingEngageFreshness.INTERACTIVE_VALIDITY_MILLIS

    @Test
    fun `frisch angeforderte Anforderung ist gueltig`() {
        assertTrue(PendingEngageFreshness.isStillValid(1_000L, 1_000L, window))
        assertTrue(PendingEngageFreshness.isStillValid(1_000L, 1_000L + window / 2, window))
    }

    /** Die Grenze selbst zählt noch als gültig — bei genau erreichter Frist soll der Nutzer nicht
     * an einer Millisekunde scheitern. */
    @Test
    fun `genau am Ende des Fensters noch gueltig, eine Millisekunde spaeter nicht mehr`() {
        assertTrue(PendingEngageFreshness.isStillValid(1_000L, 1_000L + window, window))
        assertFalse(PendingEngageFreshness.isStillValid(1_000L, 1_000L + window + 1, window))
    }

    /** Der eigentliche Befund: Widget tippen, im PIN-Gate zurückgehen, Tage später App öffnen. */
    @Test
    fun `Tage alte Anforderung ist abgelaufen`() {
        val daysLater = 3 * 24 * 60 * 60 * 1000L
        assertFalse(PendingEngageFreshness.isStillValid(1_000L, 1_000L + daysLater, window))
    }

    /**
     * `elapsedRealtime()` beginnt nach einem Neustart wieder klein — ein gespeicherter Wert größer
     * als "jetzt" kann deshalb nur von vor dem Neustart stammen. Muss abgelaufen sein, sonst
     * überlebte eine Anforderung genau den Vorgang, nach dem sie am wenigsten erwartet wird.
     */
    @Test
    fun `Zeitstempel aus der Zukunft gilt als abgelaufen, nicht als frisch`() {
        assertFalse(PendingEngageFreshness.isStillValid(900_000L, 5_000L, window))
    }

    /** Ein Eintrag aus einer Version vor diesem Ablaufdatum trägt keinen Zeitstempel. */
    @Test
    fun `fehlender Zeitstempel gilt als abgelaufen`() {
        assertFalse(PendingEngageFreshness.isStillValid(0L, 10_000L, window))
        assertFalse(PendingEngageFreshness.isStillValid(-1L, 10_000L, window))
    }

    /** Auch das Fenster selbst wird nicht blind geglaubt — ein fehlender/kaputter gespeicherter
     * Wert (`0`) darf nicht "unbegrenzt" bedeuten. */
    @Test
    fun `nicht positives Fenster gilt als abgelaufen`() {
        assertFalse(PendingEngageFreshness.isStillValid(1_000L, 1_000L, 0L))
        assertFalse(PendingEngageFreshness.isStillValid(1_000L, 1_000L, -5L))
    }

    @Test
    fun `das interaktive Fenster ist deutlich kuerzer als das des Bedrohungspfads`() {
        assertTrue(
            PendingEngageFreshness.INTERACTIVE_VALIDITY_MILLIS < PendingEngageFreshness.THREAT_VALIDITY_MILLIS,
        )
        // Beide bleiben endlich — das ist der ganze Punkt dieser Klasse.
        assertTrue(PendingEngageFreshness.INTERACTIVE_VALIDITY_MILLIS > 0)
        assertTrue(PendingEngageFreshness.THREAT_VALIDITY_MILLIS > 0)
    }

    /** Eine im längeren Fenster noch gültige Anforderung muss im kürzeren bereits abgelaufen sein —
     * sonst wären die zwei Fenster ununterscheidbar. */
    @Test
    fun `dieselbe Anforderung verfaellt im kurzen Fenster frueher als im langen`() {
        val age = PendingEngageFreshness.INTERACTIVE_VALIDITY_MILLIS + 1
        assertFalse(
            PendingEngageFreshness.isStillValid(
                1_000L,
                1_000L + age,
                PendingEngageFreshness.INTERACTIVE_VALIDITY_MILLIS,
            ),
        )
        assertTrue(
            PendingEngageFreshness.isStillValid(
                1_000L,
                1_000L + age,
                PendingEngageFreshness.THREAT_VALIDITY_MILLIS,
            ),
        )
    }
}
