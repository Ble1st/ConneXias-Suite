package de.ble1st.warden.presence

import android.util.Log
import de.ble1st.warden.crypto.PresenceProof
import de.ble1st.warden.domain.bus.RateLimiter
import de.ble1st.warden.domain.presence.DestructiveCommandGuard
import de.ble1st.warden.domain.presence.SensitiveAction
import de.ble1st.warden.domain.presence.SensitiveActionDecision
import de.ble1st.warden.domain.presence.SensitiveActionDecisionResult
import de.ble1st.warden.domain.presence.SensitiveActionOutcome
import de.ble1st.warden.logging.HashChainLogStore
import de.ble1st.warden.registry.MasterSwitchResult

/**
 * Meilenstein F.3 (Konzept Abschnitt 8/9/19): "`wipeData()`/`reboot()`/Masterschalter-Revert
 * hinter Presence + mehrstufiger Bestätigung." Verkabelt [DestructiveCommandGuard] (F.4) +
 * [RateLimiter] (Wiederverwendung, dieselbe generische, framework-freie Klasse — kein neuer,
 * bus-spezifischer Rate-Limiter nötig) + Bestätigungstext-Abgleich + [PresenceProof.consume] zu
 * [SensitiveActionDecision] (reine Entscheidung, hier ausgewertet, dort definiert).
 *
 * **`proof.consume()` läuft immer, auch wenn `executionAllowed`/`rateLimitOk` bereits `false`
 * sind:** der eigentliche Aufruf muss den Schlüssel tatsächlich *brauchen* — ein
 * Presence-Nachweis, der bei einer vorab abgelehnten Aktion einfach verworfen würde, wäre kein
 * Beweis, sondern nur eine Formalität. `SensitiveActionActivity` sollte den Guard trotzdem
 * schon *vor* dem Biometrie-Prompt prüfen (bessere UX, kein unnötiger Prompt auf einem
 * Debug-Build) — diese Klasse verlässt sich darauf aber nicht, sondern prüft unbedingt selbst
 * noch einmal (Defense-in-Depth, dieselbe Haltung wie `CapabilityMatrix`).
 *
 * **`REBOOT`/`MASTER_SWITCH_REVERT`/`LOCK_NOW`/`LOCKDOWN_MODE_ARM`/`LOCKDOWN_TASK_ENGAGE` (seit
 * 2026-08-25) real verkabelt, `WIPE_DATA` bewusst weiterhin Stub:** [performReboot]/
 * [performMasterSwitchRevert]/[performLockNow]/[performLockdownArm]/[performLockTaskEngage] sind
 * injizierte Lambdas — dasselbe Testbarkeits-Muster wie
 * `OfflineFailsafeExecutor`s `revertAllSafeguards`/`resetDeviceCredential` — und führen bei
 * [Approved] echte DPM-Operationen aus (`DevicePolicyManager.reboot()`, `MasterSwitch.disarm()`,
 * `DeviceLockNowManager.lockNow()`, `DeviceLockdownBundle`s `apply()` über die Registry).
 * `WIPE_DATA` bleibt der einzige weiterhin nur geloggte Platzhalter: es ist die einzige DPM-Aktion
 * ohne jeden Rückweg (kein Reboot/Registry-Revert/Lockdown-Scharfschalten lässt sich rückgängig
 * machen wie ein Werksreset), deshalb bewusst separat zurückgehalten statt alle gemeinsam
 * freizuschalten. `DestructiveCommandGuard` (F.4) blockiert alle real verkabelten Aktionen
 * weiterhin hart im Debug-Build — das Zielgerät läuft aktuell ausschließlich Debug-Builds, reale
 * Ausführung bleibt also weiterhin ausgeschlossen, bis explizit ein Non-Debug-Build getestet
 * wird. `LOCKDOWN_MODE_ARM` selbst hat bewusst kein eigenes Revert-Pendant hier: sein Rückweg
 * läuft über [performMasterSwitchRevert], s. `SensitiveAction`-Klassendoc.
 *
 * **Kein `SENTINEL_RESET` mehr:** anders als im ConneXias-Framework-Quellprojekt gibt es hier
 * keinen Cross-APK-Reset-Pfad mehr, s. [SensitiveAction]-Klassendoc — ein
 * [de.ble1st.warden.domain.pin.WardenPinStateDecision.LoadResult.Corrupted]-Zustand des lokalen
 * PIN-Blobs wird stattdessen über den Offline-Failsafe behandelt, kein zweiter, jetzt
 * redundanter Mechanismus in dieser Klasse.
 *
 * **Zwei Presence-Mechanismen (Threat Model T4: "Owner presence (biometric / local PIN
 * path)"):** [execute] bleibt der Biometrie-Pfad (F.1, `proof.consume()`);
 * [executeWithPinPresence] ist die Alternative für Geräte ohne Class-3-Sensor —
 * `pinPresenceGranted` kommt aus einem echten, `startActivityForResult`-vermittelten
 * `WardenPinActivity`-Aufruf (`SensitiveActionActivity`-Klassendoc), **kein** persistiertes
 * Flag. Beide Pfade laufen durch dieselbe [executeInternal] — identische Guard-Reihenfolge
 * (Debug-Build → Rate-Limit → Bestätigungstext → Presence), identisches Logging, identische
 * Aktionsausführung, nur die Art des Presence-Nachweises unterscheidet sich.
 *
 * **Rückgabetyp [SensitiveActionOutcome] statt [SensitiveActionDecisionResult] (Befund Q-5,
 * 2026-08-29):** vorher endete jeder Aufruf hier bei bestandener Prüfung mit
 * [SensitiveActionDecisionResult.Approved] — unabhängig davon, ob [runAction] danach tatsächlich
 * erfolgreich war. `SensitiveActionOutcome`s Klassendoc erklärt den vollen Fall; hier zählt nur:
 * [executeInternal] liefert bei einer Ablehnung weiterhin sofort `Denied(decision)`, bei
 * `Approved` läuft [runAction] und *dessen* Ergebnis (Erfolg/Fehler/Stub) wird zurückgegeben, nicht
 * mehr pauschal "Approved".
 */
class DestructiveActionExecutor(
    private val isDebugBuild: Boolean,
    private val logStore: HashChainLogStore,
    private val rateLimiter: RateLimiter = RateLimiter(maxCallsPerWindow = RATE_LIMIT_CALLS, windowMillis = RATE_LIMIT_WINDOW_MILLIS),
    private val performReboot: () -> Unit = {},
    private val performMasterSwitchRevert: () -> List<MasterSwitchResult> = { emptyList() },
    private val performLockNow: () -> Unit = {},
    private val performLockdownArm: () -> Unit = {},
    private val performLockTaskEngage: () -> Boolean = { false },
) {
    fun execute(action: SensitiveAction, confirmationText: String, proof: PresenceProof): SensitiveActionOutcome =
        executeInternal(action, confirmationText, presenceConsumed = proof.consume())

    /** Presence-Alternative über Wardens lokalen PIN statt Biometrie — s. Klassendoc.
     * `pinPresenceGranted` wird hier nicht selbst "konsumiert" (es gibt kein Objekt, dessen
     * Zustand invalidiert werden müsste): Androids Activity-Result-Mechanismus selbst liefert
     * bereits genau ein Ergebnis pro `WardenPinActivity`-Aufruf — dieselbe Ein-Operation-Frische
     * wie `PresenceProof.consume()`, nur plattformvermittelt statt kryptografisch. */
    fun executeWithPinPresence(
        action: SensitiveAction,
        confirmationText: String,
        pinPresenceGranted: Boolean,
    ): SensitiveActionOutcome =
        executeInternal(action, confirmationText, presenceConsumed = pinPresenceGranted)

    /**
     * WardenLock (Finalisierungsphase 2026-08-24, auf Nutzerwunsch): ein bereits beim App-Eintritt
     * erbrachter Presence-Nachweis ([de.ble1st.warden.presence.WardenLockSession]) deckt
     * [SensitiveAction.allowsSessionPresence]-Aktionen ab, ohne hier nochmal biometrisch/per PIN
     * nachzufragen. **Strukturell, nicht nur dokumentiert:** `sessionAuthenticated` allein reicht
     * für `WIPE_DATA` nie aus — `action.allowsSessionPresence` ist `false` dafür, das UND
     * degradiert zwangsläufig auf `presenceConsumed = false`, selbst wenn ein Aufrufer diese
     * Funktion versehentlich für `WIPE_DATA` aufruft (dieselbe "Defense-in-Depth"-Haltung wie beim
     * `proof.consume()`-Kommentar oben). Ein Aufrufer, der `WIPE_DATA` real ausführen will, muss
     * weiterhin [execute]/[executeWithPinPresence] mit einem frischen Nachweis benutzen.
     */
    fun executeWithSessionPresence(
        action: SensitiveAction,
        confirmationText: String,
        sessionAuthenticated: Boolean,
    ): SensitiveActionOutcome =
        executeInternal(action, confirmationText, presenceConsumed = sessionAuthenticated && action.allowsSessionPresence)

    private fun executeInternal(
        action: SensitiveAction,
        confirmationText: String,
        presenceConsumed: Boolean,
    ): SensitiveActionOutcome {
        val executionAllowed = DestructiveCommandGuard.isExecutionAllowed(isDebugBuild)
        val rateLimitOk = rateLimiter.allow(action.name)
        val confirmationTextMatches = confirmationText == action.confirmationPhrase

        val decision = SensitiveActionDecision.evaluate(executionAllowed, rateLimitOk, confirmationTextMatches, presenceConsumed)

        // "Logging vor Ausführung" (Konzept Abschnitt 8) — die Entscheidung selbst wird geloggt,
        // bevor (im Approved-Fall) die eigentliche Aktion überhaupt läuft.
        logStore.append(
            priority = if (decision == SensitiveActionDecisionResult.Approved) Log.WARN else Log.INFO,
            tag = TAG,
            message = "sensitive action $action -> $decision",
        )

        return if (decision == SensitiveActionDecisionResult.Approved) {
            runAction(action)
        } else {
            SensitiveActionOutcome.Denied(decision)
        }
    }

    private fun runAction(action: SensitiveAction): SensitiveActionOutcome = when (action) {
        SensitiveAction.WIPE_DATA -> runStub(action)
        SensitiveAction.REBOOT ->
            runSimpleAction(
                action = { performReboot() },
                successMessage = "reboot() real ausgeführt",
                failurePrefix = "reboot() fehlgeschlagen: ",
            )
        SensitiveAction.LOCK_NOW ->
            runSimpleAction(
                action = { performLockNow() },
                successMessage = "lockNow() real ausgeführt",
                failurePrefix = "lockNow() fehlgeschlagen: ",
            )
        SensitiveAction.LOCKDOWN_MODE_ARM ->
            runSimpleAction(
                action = { performLockdownArm() },
                successMessage = "DeviceLockdownBundle.apply() real ausgeführt",
                failurePrefix = "DeviceLockdownBundle.apply() fehlgeschlagen: ",
            )
        SensitiveAction.MASTER_SWITCH_REVERT -> {
            // Befund Q-5 (2026-08-29): das äußere runCatching allein reichte nicht — MasterSwitch
            // .disarm() fängt Fehler pro Safeguard bereits selbst ab und liefert eine Liste aus
            // Disarmed/Failed zurück, statt zu werfen. Ein einzelner MasterSwitchResult.Failed
            // ging deshalb bisher als "Approved" durch, obwohl der Master-Switch nicht vollständig
            // gegriffen hatte.
            val outcome = runCatching { performMasterSwitchRevert() }
            val results = outcome.getOrNull()
            val failedItems = results?.filterIsInstance<MasterSwitchResult.Failed>().orEmpty()
            when {
                outcome.isFailure -> logAndFail("MasterSwitch.disarm() fehlgeschlagen: ${outcome.exceptionOrNull()}")
                failedItems.isNotEmpty() -> logAndFail(
                    "MasterSwitch.disarm() teilweise fehlgeschlagen: " +
                        failedItems.joinToString { "${it.id}: ${it.cause}" },
                )
                else -> {
                    logStore.append(
                        priority = Log.WARN,
                        tag = TAG,
                        message = "MasterSwitch.disarm() real ausgeführt: " +
                            results.orEmpty().joinToString { "${it.id}=${it::class.simpleName}" },
                    )
                    SensitiveActionOutcome.ExecutedSuccessfully
                }
            }
        }
        SensitiveAction.LOCKDOWN_TASK_ENGAGE -> {
            // "Lockdown-Auslöse-Profil" (2026-08-27, Bug-Fix): performLockTaskEngage lieferte
            // vorher () -> Unit — ein false-Rückgabewert von SentinelLockdownEngager.engage()
            // (Sentinel nicht installiert, intern per ActivityNotFoundException abgefangen,
            // nicht geworfen) wurde dadurch stillschweigend verworfen und als Erfolg
            // protokolliert. Jetzt () -> Boolean, drei statt zwei Fälle — und (Befund Q-5,
            // 2026-08-29) der dritte ("abgelehnt") zählt jetzt ebenfalls als Fehlschlag, nicht
            // nur als abweichende Log-Zeile bei sonst weiterhin gemeldetem "Approved".
            val outcome = runCatching { performLockTaskEngage() }
            when {
                outcome.isFailure -> logAndFail("SentinelLockdownEngager.engage() fehlgeschlagen: ${outcome.exceptionOrNull()}")
                outcome.getOrThrow() -> {
                    logStore.append(priority = Log.WARN, tag = TAG, message = "SentinelLockdownEngager.engage() real angestoßen")
                    SensitiveActionOutcome.ExecutedSuccessfully
                }
                else -> logAndFail("SentinelLockdownEngager.engage() abgelehnt: Sentinel nicht installiert")
            }
        }
    }

    /** Gemeinsames Muster für die drei einfachen Ein-Aufruf-Aktionen (REBOOT, LOCK_NOW,
     * LOCKDOWN_MODE_ARM): ausführen, Ergebnis loggen, [SensitiveActionOutcome] danach ableiten
     * statt es wie vor Befund Q-5 zu verwerfen. */
    private fun runSimpleAction(action: () -> Unit, successMessage: String, failurePrefix: String): SensitiveActionOutcome {
        val outcome = runCatching(action)
        return if (outcome.isSuccess) {
            logStore.append(priority = Log.WARN, tag = TAG, message = successMessage)
            SensitiveActionOutcome.ExecutedSuccessfully
        } else {
            logAndFail("$failurePrefix${outcome.exceptionOrNull()}")
        }
    }

    private fun logAndFail(detail: String): SensitiveActionOutcome {
        logStore.append(priority = Log.WARN, tag = TAG, message = detail)
        return SensitiveActionOutcome.ExecutedWithError(detail)
    }

    private fun runStub(action: SensitiveAction): SensitiveActionOutcome {
        logStore.append(
            priority = Log.WARN,
            tag = TAG,
            message = "STUB: würde jetzt $action real ausführen (DevicePolicyManager.wipeData()) " +
                "— bewusst weiterhin nicht verkabelt, s. Klassendoc.",
        )
        return SensitiveActionOutcome.ExecutedAsStub
    }

    private companion object {
        const val TAG = "DestructiveAction"
        const val RATE_LIMIT_CALLS = 5
        const val RATE_LIMIT_WINDOW_MILLIS = 60_000L
    }
}
