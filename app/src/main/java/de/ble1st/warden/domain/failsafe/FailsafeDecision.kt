package de.ble1st.warden.domain.failsafe

/**
 * Meilenstein D.3 (Konzept Abschnitt 9/19): reine, framework-freie Entscheidungslogik für den
 * Offline-Failsafe — "bei gültiger Response `clearUserRestriction()` + PIN-Reset (nie Wipe)".
 * Bewusst getrennt von der eigentlichen Ed25519-Verifikation (die braucht die Rust-Engine, lebt
 * in [de.ble1st.warden.crypto.OfflineFailsafeVerifier], nicht framework-frei) und von den
 * DPM-Aktionen ([de.ble1st.warden.failsafe.OfflineFailsafeExecutor]) — dieselbe Trennung von
 * reiner Prüf-Logik (`domain`) und Android-Anbindung (`failsafe`/`crypto`) wie überall sonst in
 * diesem Projekt.
 *
 * `verify` wird als Funktionsparameter statt als konkrete Abhängigkeit übergeben — [evaluate]
 * bleibt dadurch ohne Keystore/JNA/Rust-Engine als reine JVM-Logik testbar (Fake-`verify`-Lambda
 * statt echtem `OfflineFailsafeVerifier`).
 */
object FailsafeDecision {
    /**
     * `verify` wird **nur** aufgerufen, wenn sowohl `pendingChallenge` als auch
     * `trustedPublicKey` vorhanden sind — beide Vorbedingungen fehlen andernfalls aussagekräftig
     * (Fail-Safe, Invariante 6: kein Failsafe ohne konfigurierten Schlüssel, keine Verifikation
     * gegen eine nicht existente Challenge).
     */
    fun evaluate(
        pendingChallenge: ByteArray?,
        trustedPublicKey: ByteArray?,
        verify: (challenge: ByteArray, publicKey: ByteArray) -> Boolean,
    ): FailsafeDecisionResult = when {
        trustedPublicKey == null -> FailsafeDecisionResult.NoKeyConfigured
        pendingChallenge == null -> FailsafeDecisionResult.NoChallengePending
        verify(pendingChallenge, trustedPublicKey) -> FailsafeDecisionResult.Accepted
        else -> FailsafeDecisionResult.Rejected
    }
}

/** Ergebnis einer [FailsafeDecision.evaluate]-Prüfung. */
sealed class FailsafeDecisionResult {
    /** Noch kein Offline-Failsafe-Schlüssel in Warden hinterlegt (Meilenstein D.1) — der
     * Failsafe kann grundsätzlich noch nicht ausgelöst werden. */
    data object NoKeyConfigured : FailsafeDecisionResult()

    /** Kein Failsafe-Durchlauf im Gange (keine Challenge wurde zuvor erzeugt) — eine Response
     * ohne zugehörige Challenge ist bedeutungslos, nie implizit "gegen die letzte irgendeine". */
    data object NoChallengePending : FailsafeDecisionResult()

    /** Response verifiziert gegen die ausstehende Challenge und den hinterlegten Schlüssel. */
    data object Accepted : FailsafeDecisionResult()

    /** Response verifiziert nicht — falscher/kein Schlüssel, falsche Challenge oder manipulierte
     * Signatur. Muss identisch zu [NoKeyConfigured]/[NoChallengePending] behandelt werden: kein
     * Zustand ändert sich (Fail-Safe, Invariante 6). */
    data object Rejected : FailsafeDecisionResult()
}
