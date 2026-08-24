package de.ble1st.warden.domain.pin

/**
 * Meilenstein H.3 (Konzept Abschnitt 19: "Argon2id-PIN-Verifikation über die Engine"). Reine,
 * framework-freie Entscheidungslogik für den PIN-Vergleich — dieselbe Trennung wie bei
 * [de.ble1st.warden.domain.failsafe.FailsafeDecision]: die eigentliche
 * Argon2id-Prüfung braucht die Rust-Engine (`Engine.verifyPassword`, `:core:crypto`), lebt aber
 * bewusst nicht hier, damit [evaluate] ohne Keystore/JNA als reine JVM-Logik testbar bleibt.
 *
 * **Kein Anti-Hammering/Rate-Limiting hier** (Meilenstein H.7, zurückgestellt) — [evaluate]
 * trifft ausschließlich die reine Ja/Nein-Entscheidung für einen einzelnen Versuch, keine
 * Sperrlogik über mehrere Versuche hinweg.
 */
object WardenPinDecision {
    /**
     * `verify` wird **nur** aufgerufen, wenn bereits ein PIN-Hash konfiguriert ist — ohne
     * hinterlegten Hash gibt es nichts, wogegen zu prüfen wäre (Fail-Safe, Invariante 6: kein
     * Vergleich gegen einen nicht existenten Soll-Wert, der zufällig als "gültig" durchgehen
     * könnte).
     */
    fun evaluate(
        storedHash: String?,
        enteredPin: ByteArray,
        verify: (pin: ByteArray, hash: String) -> Boolean,
    ): WardenPinDecisionResult = when {
        storedHash == null -> WardenPinDecisionResult.NotConfigured
        verify(enteredPin, storedHash) -> WardenPinDecisionResult.Accepted
        else -> WardenPinDecisionResult.Rejected
    }
}

/** Ergebnis einer [WardenPinDecision.evaluate]-Prüfung. */
sealed class WardenPinDecisionResult {
    /** Noch kein PIN-Hash hinterlegt — Ersteinrichtungs-Zustand. */
    data object NotConfigured : WardenPinDecisionResult()

    /** Eingegebene PIN verifiziert gegen den hinterlegten Argon2id-Hash. */
    data object Accepted : WardenPinDecisionResult()

    /** Eingegebene PIN verifiziert nicht gegen den hinterlegten Hash. Muss identisch zu
     * [NotConfigured] behandelt werden, was den PIN-Zustand angeht: nichts wird entsperrt
     * (Fail-Safe, Invariante 6). */
    data object Rejected : WardenPinDecisionResult()
}
