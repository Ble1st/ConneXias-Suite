package de.ble1st.warden.domain.presence

/**
 * Meilenstein F.3 (Konzept Abschnitt 8/9/2b/(7)): reine Entscheidungslogik für eine sensible
 * Aktion — dieselbe Trennung wie bei `FailsafeDecision` (D.3): [evaluate] kennt weder
 * `PresenceProof`/Keystore (`:core:crypto`) noch `DevicePolicyManager`/`MasterSwitch`
 * (`:core:data`/`:warden-app`), alle vier Bedingungen werden als bereits ausgewertete Booleans
 * hereingereicht — framework-frei, ohne Fake für Biometrie/DPM testbar.
 *
 * **Reihenfolge der Prüfungen ist bewusst fest** (nicht die Reihenfolge, in der ein Nutzer die
 * Schritte durchläuft, sondern Prüf-Priorität): ein Debug-Build wird abgelehnt, *bevor* Rate-
 * Limit/Bestätigungstext/Presence überhaupt eine Rolle spielen — F.4s "hart abschalten" heißt
 * hart, nicht "nachrangig zu anderen Gründen".
 */
object SensitiveActionDecision {
    fun evaluate(
        executionAllowed: Boolean,
        rateLimitOk: Boolean,
        confirmationTextMatches: Boolean,
        presenceConsumed: Boolean,
    ): SensitiveActionDecisionResult = when {
        !executionAllowed -> SensitiveActionDecisionResult.ExecutionBlocked
        !rateLimitOk -> SensitiveActionDecisionResult.RateLimited
        !confirmationTextMatches -> SensitiveActionDecisionResult.WrongConfirmationText
        !presenceConsumed -> SensitiveActionDecisionResult.PresenceNotProven
        else -> SensitiveActionDecisionResult.Approved
    }
}

/** Ergebnis von [SensitiveActionDecision.evaluate]. */
sealed class SensitiveActionDecisionResult {
    /** Debug-Build (Konzept F.4: "`BuildConfig.DEBUG` schaltet destruktive Kommandos hart ab") —
     * die schärfste, zuerst geprüfte Ablehnung. */
    data object ExecutionBlocked : SensitiveActionDecisionResult()

    /** Zu viele Versuche in kurzer Zeit (Konzept Abschnitt 8: "Rate-Limiting/Cooldown"). */
    data object RateLimited : SensitiveActionDecisionResult()

    /** Der eingetippte Bestätigungstext passt nicht zu `SensitiveAction.confirmationPhrase`. */
    data object WrongConfirmationText : SensitiveActionDecisionResult()

    /** Kein gültiger, frisch konsumierter [de.ble1st.warden.crypto.PresenceProof]
     * — F.1/2b/(7): eine bloße Behauptung "Presence war da" genügt nie. */
    data object PresenceNotProven : SensitiveActionDecisionResult()

    /** Alle vier Bedingungen erfüllt — die Aktion darf ausgeführt werden. */
    data object Approved : SensitiveActionDecisionResult()
}
