package de.ble1st.warden.domain.pin

/**
 * Wie lange eine vorgemerkte, noch nicht abgeholte Scharfschalt-Anforderung gültig bleibt
 * (2026-09-05, auf Nutzerwunsch nach dem Gerätetest der Widget-Schnellaktionen).
 *
 * **Der Fehler, den das schließt.** Die beiden Übergabepunkte
 * ([de.ble1st.warden.pin.WardenLockTaskPendingEngageStore],
 * [de.ble1st.warden.pin.WardenLockdownArmPendingEngageStore]) merken eine Anforderung vor und
 * geben sie beim nächsten `WardenStatusActivity.onResume()` heraus — bis dahin *unbegrenzt*. Im
 * Gerätetest fiel der naheliegende Weg dorthin auf: Widget-Schaltfläche tippen, im
 * `WardenLockActivity`-PIN-Gate zurückgehen. `lockLauncher` ruft dann `finish()`, ohne dass je
 * konsumiert wurde — die Anforderung blieb liegen und hätte den Dialog "Lockdown scharf schalten?"
 * beim *nächsten* App-Start gezeigt, unter Umständen Tage später und ohne erkennbaren Anlass. Ein
 * Dialog, dessen Auslöser der Nutzer nicht mehr erinnert, ist genau der, den man wegtippt.
 *
 * **Monotone Uhr, nicht Wanduhr.** Verglichen wird `SystemClock.elapsedRealtime()` (die Aufrufer
 * lesen sie, diese Klasse bleibt framework-frei). Zwei Gründe, beide inhaltlich:
 * 1. `System.currentTimeMillis()` lässt sich verstellen — und dieses Projekt hat mit
 *    [TimeIntegrityMode] ein eigenes Auswahlmenü dagegen. Ein Ablaufdatum, das sich durch
 *    Zurückstellen der Uhr verlängern lässt, wäre keins.
 * 2. `elapsedRealtime()` beginnt bei jedem Neustart neu. Ein gespeicherter Wert, der *größer* ist
 *    als der aktuelle, kann deshalb nur von vor einem Neustart stammen — und eine Anforderung, die
 *    einen Neustart überlebt hat, soll ohnehin nicht mehr feuern. Genau dieser Fall fällt hier als
 *    "abgelaufen" heraus, ohne dass ein Neustart eigens erkannt werden müsste.
 *
 * **Zwei Fenster, weil es zwei Arten von Anforderung gibt** — und die kürzere pauschal auf beide
 * anzuwenden würde eine bestehende, absichtlich so gebaute Funktion beschädigen:
 * - [INTERACTIVE_VALIDITY_MILLIS] für Widget und Quick-Settings-Kachel: der Nutzer hat *gerade
 *   eben* getippt, der Weg bis `onResume()` sind Sekunden plus PIN-Eingabe.
 * - [THREAT_VALIDITY_MILLIS] für den Bedrohungs-Scan-Pfad
 *   ([de.ble1st.warden.appmanagement.SuspiciousAppScanController]). Der wartet bewusst darauf, dass
 *   die Betreiberin Warden als Reaktion auf die Benachrichtigung öffnet — das darf auch mal eine
 *   Stunde dauern (s. dessen Store-Klassendoc: "ein Mensch ist damit anwesend"). Begrenzt ist es
 *   trotzdem: ein eine Woche alter Fund soll kein Kiosk mehr auslösen.
 */
object PendingEngageFreshness {

    /** Widget-Schnellaktion und Quick-Settings-Kachel. */
    const val INTERACTIVE_VALIDITY_MILLIS: Long = 5 * 60 * 1000L

    /** Automatischer Auslöser aus einem kritischen Bedrohungsfund. */
    const val THREAT_VALIDITY_MILLIS: Long = 24 * 60 * 60 * 1000L

    /**
     * [requestedAtElapsedMillis]/[nowElapsedMillis] sind Werte von `SystemClock.elapsedRealtime()`.
     *
     * Alles, was nicht eindeutig frisch ist, gilt als abgelaufen — dieselbe Fail-Safe-Richtung wie
     * überall sonst, hier zusätzlich naheliegend, weil "abgelaufen" nur bedeutet, dass der Nutzer
     * die Schaltfläche noch einmal tippt:
     * - kein/ungültiger Zeitstempel (`<= 0`, etwa ein Eintrag aus einer Version vor diesem
     *   Ablaufdatum) → abgelaufen,
     * - Zeitstempel in der Zukunft → abgelaufen (Neustart, s. Klassendoc),
     * - [validityMillis] `<= 0` → abgelaufen.
     */
    fun isStillValid(
        requestedAtElapsedMillis: Long,
        nowElapsedMillis: Long,
        validityMillis: Long,
    ): Boolean {
        if (requestedAtElapsedMillis <= 0L) return false
        if (validityMillis <= 0L) return false
        if (nowElapsedMillis < requestedAtElapsedMillis) return false
        return nowElapsedMillis - requestedAtElapsedMillis <= validityMillis
    }
}
