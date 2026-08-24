package de.ble1st.warden.failsafe

import de.ble1st.warden.crypto.OfflineFailsafeVerifier
import de.ble1st.warden.domain.failsafe.FailsafeChallengeTtl
import de.ble1st.warden.domain.failsafe.FailsafeDecision
import de.ble1st.warden.domain.failsafe.FailsafeDecisionResult
import de.ble1st.warden.domain.failsafe.FailsafeDeviceCredentialPolicy
import de.ble1st.warden.registry.MasterSwitchResult

/**
 * Meilenstein D.3 (Konzept Abschnitt 9/19): "Failsafe-Aktion: bei gültiger Response
 * `clearUserRestriction()` + PIN-Reset (nie Wipe); Ende-zu-Ende testen." Verkabelt die reine
 * Entscheidungslogik ([FailsafeDecision], `:core:domain`) mit der Ed25519-Verifikation
 * ([OfflineFailsafeVerifier], `:core:crypto`) und den beiden DPM-Aktionen — "alles zurücksetzen"
 * (`MasterSwitch.disarm`, Meilenstein C.6) und "Gerätepasswort neu setzen"
 * ([DeviceCredentialResetter]).
 *
 * `revertAllSafeguards`/`resetDeviceCredential` werden als Funktionsparameter injiziert statt
 * konkrete `MasterSwitch`-/`DeviceCredentialResetter`-Instanzen zu verlangen — macht diese Klasse
 * mit Fakes testbar, ohne echtes DPM (analog zur Trennung, die [FailsafeDecision] bereits für die
 * Verifikation vorgibt).
 *
 * **Nur bei [FailsafeDecisionResult.Accepted] werden `revertAllSafeguards`/
 * `resetDeviceCredential` überhaupt aufgerufen** — jeder andere Fall ändert am Geräte- oder
 * Registry-Zustand nichts (Fail-Safe, Invariante 6). Die verbrauchte Challenge wird **nur** nach
 * einem tatsächlichen `Accepted`-Durchlauf gelöscht ([FailsafeChallengeStore.clearChallenge]) —
 * bei `Rejected` bleibt sie bestehen, damit ein Vertipper bei der Response-Eingabe nicht gleich
 * die ganze Challenge verbrennt und ein neuer Offline-Signiervorgang nötig wird.
 *
 * Weak device credentials are rejected *before* verify so a valid signed response can be
 * retried with a stronger PIN. An expired challenge is cleared so a stolen signature cannot
 * wait for a later clock change. If credential reset fails after Accepted, the challenge is
 * kept so the owner can retry the OS lock reset without re-signing.
 */
class OfflineFailsafeExecutor(
    private val challengeStore: FailsafeChallengeStore,
    private val keyStore: FailsafeKeyStore,
    private val verifier: OfflineFailsafeVerifier,
    private val revertAllSafeguards: () -> List<MasterSwitchResult>,
    private val resetDeviceCredential: (newPassword: String) -> Boolean,
    private val resetLocalPinSecrets: () -> Unit = {},
    private val onEvent: (OfflineFailsafeEvent) -> Unit = {},
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {

    /** Erzeugt und persistiert eine neue Challenge zur Anzeige (Konzept: "Challenge auf dem
     * Gerät erzeugt/angezeigt"). */
    fun issueChallenge(): ByteArray {
        val challenge = challengeStore.issueNewChallenge(nowEpochMs = nowEpochMs())
        onEvent(OfflineFailsafeEvent.ChallengeIssued(challenge))
        return challenge
    }

    fun submitResponse(response: ByteArray, newDeviceCredential: String): OfflineFailsafeResult {
        if (!FailsafeDeviceCredentialPolicy.isAcceptable(newDeviceCredential)) {
            val result = OfflineFailsafeResult.WeakCredential
            onEvent(OfflineFailsafeEvent.ResponseSubmitted(result))
            return result
        }

        val record = challengeStore.pendingRecord()
        if (record != null && FailsafeChallengeTtl.isExpired(record.issuedAtEpochMs, nowEpochMs())) {
            challengeStore.clearChallenge()
            val result = OfflineFailsafeResult.ChallengeExpired
            onEvent(OfflineFailsafeEvent.ResponseSubmitted(result))
            return result
        }

        val pendingChallenge = record?.challenge
        val trustedPublicKey = keyStore.configuredPublicKey()

        val decision = FailsafeDecision.evaluate(pendingChallenge, trustedPublicKey) { challenge, publicKey ->
            verifier.verify(challenge, response, publicKey)
        }

        val result = when (decision) {
            FailsafeDecisionResult.NoKeyConfigured -> OfflineFailsafeResult.NoKeyConfigured
            FailsafeDecisionResult.NoChallengePending -> OfflineFailsafeResult.NoChallengePending
            FailsafeDecisionResult.Rejected -> OfflineFailsafeResult.Rejected
            FailsafeDecisionResult.Accepted -> {
                val revertResults = revertAllSafeguards()
                val credentialResetSucceeded = resetDeviceCredential(newDeviceCredential)
                resetLocalPinSecrets()
                if (credentialResetSucceeded) {
                    challengeStore.clearChallenge()
                }
                OfflineFailsafeResult.Accepted(revertResults, credentialResetSucceeded)
            }
        }

        onEvent(OfflineFailsafeEvent.ResponseSubmitted(result))
        return result
    }
}

/** Ergebnis eines [OfflineFailsafeExecutor.submitResponse]-Aufrufs. */
sealed class OfflineFailsafeResult {
    /** Kein Failsafe-Schlüssel hinterlegt — Failsafe kann grundsätzlich nicht ausgelöst werden. */
    data object NoKeyConfigured : OfflineFailsafeResult()

    /** Keine Challenge ausstehend — [OfflineFailsafeExecutor.issueChallenge] wurde nicht (mehr)
     * aufgerufen. */
    data object NoChallengePending : OfflineFailsafeResult()

    /** Stored challenge older than [FailsafeChallengeTtl] — cleared, must issue a new one. */
    data object ChallengeExpired : OfflineFailsafeResult()

    /** New device lock rejected by [FailsafeDeviceCredentialPolicy] — challenge kept. */
    data object WeakCredential : OfflineFailsafeResult()

    /** Response verifiziert nicht — kein Zustand wurde geändert, die Challenge bleibt bestehen. */
    data object Rejected : OfflineFailsafeResult()

    /**
     * Response verifiziert, Failsafe-Aktion durchgeführt. `revertResults` enthält das Ergebnis
     * pro Registry-Eintrag (s. `MasterSwitch.disarm`) — auch bei `Accepted` können einzelne
     * Einträge fehlgeschlagen sein ([MasterSwitchResult.Failed]), das bricht den Gesamtdurchlauf
     * nicht ab (dieselbe Fehlerisolierung wie überall in der Registry-Kette). `credentialResetSucceeded`
     * spiegelt den Rückgabewert von `DevicePolicyManager.resetPasswordWithToken` — `false` heißt,
     * dass die Registry zwar zurückgesetzt wurde, das Gerätepasswort aber nicht (z. B. inaktives
     * Token) und ein weiterer Versuch mit erneut aktiviertem Token nötig ist.
     */
    data class Accepted(
        val revertResults: List<MasterSwitchResult>,
        val credentialResetSucceeded: Boolean,
    ) : OfflineFailsafeResult()
}

/** Ereignisse für Aufrufer, die den Ablauf protokollieren wollen (`:warden-app`s
 * `HashChainLogStore`-Anbindung, s. `FailsafeActivity`) — bewusst dasselbe Callback-Muster wie
 * `MasterSwitch`/`RegistryReconciler`. */
sealed class OfflineFailsafeEvent {
    data class ChallengeIssued(val challenge: ByteArray) : OfflineFailsafeEvent()
    data class ResponseSubmitted(val result: OfflineFailsafeResult) : OfflineFailsafeEvent()
}
