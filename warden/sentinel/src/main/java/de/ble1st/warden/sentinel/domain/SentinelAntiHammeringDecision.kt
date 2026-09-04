package de.ble1st.warden.sentinel.domain

/**
 * 1:1 Port von `de.ble1st.warden.domain.pin.WardenAntiHammeringDecision` (s. dessen Klassendoc
 * für die volle Begründung: exponentieller Backoff ab dem 5. Fehlversuch, im Blob persistiert,
 * überlebt einen Reboot). Reine Berechnung, framework-frei — Sentinels eigene, unabhängige Kopie
 * (Plan-Entscheidung "Crypto-Sharing: Duplizieren", gilt genauso für diese reine Logikklasse:
 * kein Grund, dafür ein Shared-Modul aufzuspannen).
 */
object SentinelAntiHammeringDecision {

    private const val BACKOFF_START_ATTEMPT = 5
    private const val BACKOFF_BASE_SECONDS = 1L
    private const val BACKOFF_CAP_SECONDS = 3600L
    private const val MAX_EXPONENT = 20

    fun backoffSecondsFor(failedAttempts: Int): Long {
        if (failedAttempts < BACKOFF_START_ATTEMPT) return 0
        val exponent = (failedAttempts - BACKOFF_START_ATTEMPT).coerceAtMost(MAX_EXPONENT)
        val seconds = BACKOFF_BASE_SECONDS shl exponent
        return seconds.coerceAtMost(BACKOFF_CAP_SECONDS)
    }

    fun isAttemptAllowedNow(backoffUntilEpochSeconds: Long, nowEpochSeconds: Long): Boolean =
        nowEpochSeconds >= backoffUntilEpochSeconds
}
