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

/**
 * Ergebnis eines kompletten [de.ble1st.warden.presence.DestructiveActionExecutor]-Aufrufs (Befund
 * Q-5, 2026-08-29) — mehr, als [SensitiveActionDecisionResult] allein ausdrücken kann. Vorher gab
 * `executeInternal()` bei bestandener Prüfung immer [SensitiveActionDecisionResult.Approved]
 * zurück, unabhängig davon, ob die *tatsächliche* Aktion danach (`performReboot()`,
 * `MasterSwitch.disarm()`, `SentinelLockdownEngager.engage()`, …) erfolgreich lief oder warf — der
 * Audit-Log-Eintrag hielt einen Fehlschlag zwar fest, aber die UI zeigte trotzdem
 * "✓ real ausgeführt und protokolliert." Genau der Fall, für den anderswo im Projekt die
 * `List<T>?`-Konvention existiert ("fehlgeschlagen" darf nie wie "in Ordnung" aussehen), fehlte
 * hier.
 *
 * Framework-frei wie [SensitiveActionDecisionResult] selbst — [ExecutedWithError.detail] ist
 * bewusst ein bereits fertig formatierter `String` (derselbe Text, der auch im Audit-Log-Eintrag
 * steht), kein `Throwable`: die Ausführungsschicht (`presence/DestructiveActionExecutor`) kennt die
 * konkreten Fehlerquellen (Exception, oder — bei `MASTER_SWITCH_REVERT`/`LOCKDOWN_TASK_ENGAGE` —
 * ein rein logischer Teilfehlschlag ohne jede Exception), dieser Typ muss sie nicht kennen.
 */
sealed class SensitiveActionOutcome {
    /** Eine der vier Prüfungen in [SensitiveActionDecision.evaluate] hat abgelehnt — die Aktion
     * ist nie angelaufen. [reason] ist strukturell nie [SensitiveActionDecisionResult.Approved]
     * (dieser Fall führt stattdessen zu einem der drei anderen Varianten hier). */
    data class Denied(val reason: SensitiveActionDecisionResult) : SensitiveActionOutcome()

    /** Geprüft, ausgeführt, ohne Fehler zurückgekehrt. */
    data object ExecutedSuccessfully : SensitiveActionOutcome()

    /** Geprüft, ausgeführt — aber die eigentliche Operation ist fehlgeschlagen (Exception), oder
     * bei `MASTER_SWITCH_REVERT`/`LOCKDOWN_TASK_ENGAGE` nur teilweise/gar nicht angekommen, ohne
     * dass unterwegs eine Exception geworfen wurde. */
    data class ExecutedWithError(val detail: String) : SensitiveActionOutcome()

    /** `WIPE_DATA` — bewusst weiterhin nur geloggter Platzhalter, s.
     * `DestructiveActionExecutor`-Klassendoc. */
    data object ExecutedAsStub : SensitiveActionOutcome()
}
