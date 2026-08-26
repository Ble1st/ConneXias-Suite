package de.ble1st.warden.pin

import android.app.Activity
import de.ble1st.warden.domain.pin.WardenLockTaskGate

/**
 * Meilenstein H.8 (Konzept Abschnitt 7). Dünner Wrapper um `Activity.startLockTask()`/
 * `stopLockTask()` — funktioniert nur, wenn Warden zuvor
 * `WardenLockTaskAuthorizer.apply()` aufgerufen hat.
 *
 * **Seit "LockMode/Threat-Protection-Ausbau" (2026-08-25) real verkabelt** — zwei Aufrufer:
 * `de.ble1st.warden.presence.SensitiveActionActivity` (manuell, presence-gated über
 * `de.ble1st.warden.domain.presence.SensitiveAction.LOCKDOWN_TASK_ENGAGE`) und
 * `de.ble1st.warden.ui.WardenStatusActivity.consumePendingLockTaskEngage` (automatisch bei
 * kritischen Bedrohungsfunden, s. `de.ble1st.warden.domain.pin
 * .WardenLockTaskAutoEngageDecision`-Klassendoc für die vier dafür nötigen Bedingungen). Beide
 * Aufrufer schalten zusätzlich `de.ble1st.warden.domain.presence.DestructiveCommandGuard`
 * davor — reales `startLockTask()` auf einem Debug-Build zu riskieren, ohne zuvor den
 * Notruf-Escape-Pfad real verifiziert zu haben, könnte das Gerät im Lock-Task-Modus aussperren
 * (dieselbe Vorsichts-Kategorie wie `DISALLOW_FACTORY_RESET`/`DISALLOW_SAFE_BOOT`) — auf dem
 * aktuellen, ausnahmslos als Debug-Build laufenden Testgerät bleibt reale Ausführung also
 * weiterhin ausgeschlossen. [WardenLockTaskGate] verweigert [startIfPermitted] zusätzlich
 * strukturell, solange kein echter, manuell durchgeführter Notruf-Drill bestätigt wurde
 * (`emergencyCallDrillPassed`-Parameter — beide Aufrufer lesen ihn aus
 * `de.ble1st.warden.pin.WardenLockTaskDrillStorage`, einem *persistierten*, aber ausschließlich
 * über einen expliziten, bestätigungstextgeschützten UI-Flow gesetzten Bit, nie hartkodiert
 * `true`, s. dessen Klassendoc). **Offener Folgeschritt, weiterhin nicht Teil dieser Runde:**
 * ein realer Notruf-Drill auf einem Non-Debug-Build muss vor dem ersten produktiven Einsatz noch
 * durchgeführt und dokumentiert werden, s. `MANUAL_SMOKE_TEST.md`.
 */
class WardenLockTaskManager(private val activity: Activity) {

    fun startIfPermitted(emergencyCallDrillPassed: Boolean): Boolean {
        if (!WardenLockTaskGate.isLockTaskPermitted(emergencyCallDrillPassed)) return false
        activity.startLockTask()
        return true
    }

    fun stop() {
        activity.stopLockTask()
    }
}
