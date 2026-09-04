package de.ble1st.warden.sentinel.domain

/**
 * 1:1 Port von `de.ble1st.warden.domain.pin.WardenPinDecision` — reine Ja/Nein-Entscheidung für
 * einen einzelnen PIN-Vergleich, kein Anti-Hammering hier (s. [SentinelAntiHammeringDecision]).
 * `verify` bleibt injiziert, damit diese Klasse ohne die Rust-Engine als reine JVM-Logik testbar
 * bleibt (gleiche Trennung wie im Rest des Projekts).
 */
object SentinelPinDecision {
    fun evaluate(
        storedHash: String?,
        enteredPin: ByteArray,
        verify: (pin: ByteArray, hash: String) -> Boolean,
    ): SentinelPinDecisionResult = when {
        storedHash == null -> SentinelPinDecisionResult.NotConfigured
        verify(enteredPin, storedHash) -> SentinelPinDecisionResult.Accepted
        else -> SentinelPinDecisionResult.Rejected
    }
}

sealed class SentinelPinDecisionResult {
    data object NotConfigured : SentinelPinDecisionResult()
    data object Accepted : SentinelPinDecisionResult()
    data object Rejected : SentinelPinDecisionResult()
}
