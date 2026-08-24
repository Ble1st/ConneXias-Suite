package de.ble1st.warden.domain.pin

/**
 * Meilenstein H.7 (Konzept Abschnitt 6: "Anti-Hammering: exponentieller Backoff ab 5.
 * Fehlversuch, im Blob persistiert (Reboot setzt nicht zurück), Eskalation an Warden statt
 * Datenvernichtung; Notruf bleibt unberührt"). Reine Berechnung — der Aufrufer
 * (`WardenPinActivity`) liest `failedAttempts`/`backoffUntilEpochSeconds` aus dem persistierten
 * [WardenPinBlob], nicht aus einem Prozessspeicher-Zähler, der einen Reboot nicht überleben würde.
 *
 * **"Notruf bleibt unberührt":** strukturell dadurch erfüllt, dass diese Klasse den
 * Notruf-/Lock-Task-Pfad (`WardenLockTaskGate`) gar nicht kennt — der Backoff blockiert
 * ausschließlich `WardenPinDecision.evaluate`-Aufrufe, nie den systemeigenen Notruf-Weg.
 *
 * **Konstantzeit-Vergleich:** der eigentliche Geheimnisvergleich läuft über
 * `Engine.verifyPassword` (Argon2id, Rust-Engine) — kein eigener naiver `==`/`contentEquals` auf
 * Klartext-Geheimnissen an dieser Stelle nötig.
 */
object WardenAntiHammeringDecision {

    private const val BACKOFF_START_ATTEMPT = 5
    private const val BACKOFF_BASE_SECONDS = 1L
    private const val BACKOFF_CAP_SECONDS = 3600L // 1 Stunde
    private const val MAX_EXPONENT = 20 // 2^20 s ≈ 12 Tage — weit über dem Cap, verhindert nur einen Long-Shift-Wraparound

    /** Backoff-Dauer in Sekunden für den *nächsten* Versuch, gegeben die Anzahl bisheriger
     * Fehlversuche `failedAttempts`. `0`, solange unter [BACKOFF_START_ATTEMPT] — kein Backoff
     * für die ersten paar plausiblen Vertipper. */
    fun backoffSecondsFor(failedAttempts: Int): Long {
        if (failedAttempts < BACKOFF_START_ATTEMPT) return 0
        val exponent = (failedAttempts - BACKOFF_START_ATTEMPT).coerceAtMost(MAX_EXPONENT)
        val seconds = BACKOFF_BASE_SECONDS shl exponent
        return seconds.coerceAtMost(BACKOFF_CAP_SECONDS)
    }

    /** Ob jetzt (`nowEpochSeconds`) ein neuer Versuch erlaubt ist, gegeben den zuletzt
     * persistierten `backoffUntilEpochSeconds`-Wert aus dem Blob. `nowEpochSeconds` als Parameter
     * statt intern `System.currentTimeMillis()` — testbar ohne Uhr-Mocking. */
    fun isAttemptAllowedNow(backoffUntilEpochSeconds: Long, nowEpochSeconds: Long): Boolean =
        nowEpochSeconds >= backoffUntilEpochSeconds
}
