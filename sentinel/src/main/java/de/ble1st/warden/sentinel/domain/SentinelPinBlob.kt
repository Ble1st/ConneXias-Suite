package de.ble1st.warden.sentinel.domain

/**
 * Sentinels PIN-Zustand — deutlich abgespeckt gegenüber Wardens `WardenPinBlob`
 * (`counter`/`previousHash`-Hash-Kette bewusst weggelassen, s. [SentinelPinBlobCodec]-Klassendoc)
 * und gegenüber dem alten ConneXias-Framework-`SentinelBlob` (kein `locked`-Feld — wird von
 * keiner Entscheidungslogik gelesen, s. Warden-Vorbild; kein Cross-APK-Rollback-Zähler, s.
 * Plan-Abschnitt "Explizit außerhalb dieses Plans"). Nur was
 * [SentinelAntiHammeringDecision]/[SentinelPinDecision] tatsächlich brauchen.
 */
data class SentinelPinBlob(
    val pinHash: String,
    val failedAttempts: Int,
    val backoffUntilEpochSeconds: Long,
) {
    init {
        require(failedAttempts >= 0) { "failedAttempts darf nicht negativ sein" }
    }

    companion object {
        fun genesis(): SentinelPinBlob = SentinelPinBlob(
            pinHash = "",
            failedAttempts = 0,
            backoffUntilEpochSeconds = 0,
        )
    }
}
