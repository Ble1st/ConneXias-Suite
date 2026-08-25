package de.ble1st.warden.domain.pin

import de.ble1st.warden.domain.appmanagement.ThreatSeverity

/**
 * "LockMode/Threat-Protection-Ausbau" (2026-08-25) — reine, framework-freie Entscheidungslogik
 * (dieselbe Decision/Executor-Trennung wie überall sonst im Projekt) für den einzigen Punkt, an
 * dem ein Bedrohungsfund automatisch — ohne einen frischen Presence-Nachweis in dem Moment — ein
 * `startLockTask()` anfordern darf. **Vier unabhängige Bedingungen, alle `true`:**
 *
 * 1. [severity] `== CRITICAL` — nur ein Fund, der bereits einen Übergang *im Gange* beschreibt
 *    (s. [ThreatSeverity]-Klassendoc), nicht eine bloße Deklarationsmöglichkeit.
 * 2. [drillConfirmed] — die einmalige, manuelle, physische Notruf-Drill-Bestätigung liegt vor
 *    (s. [WardenLockTaskGate]/`de.ble1st.warden.pin.WardenLockTaskDrillStorage`).
 * 3. [lockdownArmed] — `DeviceLockdownBundle`/`SensitiveAction.LOCKDOWN_MODE_ARM` ist bereits
 *    presence-gated scharf geschaltet; automatisches Engage ist der letzte Schritt eines bereits
 *    bewusst verschärften Zustands, kein Sprung aus dem Alltagsbetrieb heraus.
 * 4. [autoEngageEnabled] — der eigene, dritte Opt-in
 *    (`de.ble1st.warden.pin.WardenLockTaskAutoEngageStore`), separat vom Lockdown-Scharfschalten
 *    selbst.
 *
 * **[isDebugBuild] wird hier bewusst NICHT geprüft** — diese Funktion entscheidet nur, ob eine
 * Anforderung *vorgemerkt* wird ([de.ble1st.warden.pin.WardenLockTaskPendingEngageStore
 * .requestEngage]), nicht ob `startLockTask()` tatsächlich läuft. Der Debug-Build-Hardblock
 * ([de.ble1st.warden.domain.presence.DestructiveCommandGuard]) greift stattdessen dort, wo die
 * Anforderung tatsächlich eingelöst wird (`WardenLockTaskManager.startIfPermitted` über
 * `SensitiveAction.LOCKDOWN_TASK_ENGAGE`) — eine vorgemerkte, aber nie ausgeführte Anforderung auf
 * einem Debug-Build ist unkritisch und lässt sich am realen Verhalten prüfen, ohne das Testgerät
 * zu gefährden.
 */
object WardenLockTaskAutoEngageDecision {

    fun shouldRequestEngage(
        severity: ThreatSeverity,
        drillConfirmed: Boolean,
        lockdownArmed: Boolean,
        autoEngageEnabled: Boolean,
    ): Boolean = severity == ThreatSeverity.CRITICAL && drillConfirmed && lockdownArmed && autoEngageEnabled
}
