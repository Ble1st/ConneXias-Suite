package de.ble1st.warden.pin

import android.content.Context
import androidx.core.content.edit

/**
 * "LockMode/Threat-Protection-Ausbau" (2026-08-25, auf Nutzerwunsch, nach ausdrücklicher Klärung
 * per Rückfrage: "Persistente Bestätigung + Trigger"). Persistiert genau das eine Bit, das
 * [de.ble1st.warden.domain.pin.WardenLockTaskGate.isLockTaskPermitted] als
 * `emergencyCallDrillPassed` braucht — bisher musste jeder Aufrufer diesen Wert selbst
 * mitbringen (dessen Klassendoc: "vom Aufrufer explizit übergeben — kein gespeichertes/
 * implizites Flag, das versehentlich `true` bleiben könnte"), was in der Praxis hieß: es gab
 * überhaupt keinen Aufrufer, `WardenLockTaskManager.startIfPermitted()` lief nirgends.
 *
 * **Weiterhin niemals implizit `true`:** [confirm] wird ausschließlich von einem UI-Flow
 * aufgerufen, der zuvor einen exakt einzutippenden Bestätigungstext verlangt (s.
 * `de.ble1st.warden.ui.SafeguardsScreen`s `EmergencyDrillConfirmationRow`) — dieselbe
 * Tippfehler-Schutz-Idee wie [de.ble1st.warden.presence.SensitiveActionActivity]s
 * Bestätigungstext, hier aber bewusst *nicht* über [de.ble1st.warden.presence
 * .DestructiveActionExecutor] geroutet: das Bestätigen selbst ist keine destruktive DPM-Aktion
 * (sie liest/schreibt nur dieses eine lokale Bit), `DestructiveCommandGuard`s Debug-Build-Hardblock
 * darf das Festhalten eines bereits real durchgeführten Notruf-Tests nicht verhindern — sonst
 * ließe sich die Bestätigung auf dem aktuellen Debug-Testgerät nie vorbereiten, selbst wenn der
 * Drill tatsächlich stattgefunden hat. Der Debug-Build-Hardblock bleibt trotzdem lückenlos wirksam:
 * er sitzt beim tatsächlichen `startLockTask()`-Aufruf (`WardenLockTaskManager`/
 * `SensitiveAction.LOCKDOWN_TASK_ENGAGE` über [de.ble1st.warden.presence
 * .DestructiveActionExecutor]), nicht hier.
 *
 * [revoke] ist bewusst ungegatet (kein Bestätigungstext nötig) — Zurücknehmen ist immer die
 * risikofreie Richtung, dieselbe Asymmetrie wie bei
 * `de.ble1st.warden.registry.UserRestrictionSafeguard.debuggingFeaturesDisabled`s
 * `ConfirmBeforeEnableEntryRow`.
 */
object WardenLockTaskDrillStorage {
    private const val PREFS_NAME = "warden_lock_task_drill"
    private const val KEY_CONFIRMED = "confirmed"
    private const val KEY_CONFIRMED_AT_MILLIS = "confirmed_at_millis"

    fun isConfirmed(context: Context): Boolean = prefs(context).getBoolean(KEY_CONFIRMED, false)

    /** `null`, solange nie bestätigt — für eine informative Zeitangabe in der UI. */
    fun confirmedAtMillis(context: Context): Long? =
        prefs(context).getLong(KEY_CONFIRMED_AT_MILLIS, -1L).takeIf { it >= 0 }

    fun confirm(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit {
            putBoolean(KEY_CONFIRMED, true)
            putLong(KEY_CONFIRMED_AT_MILLIS, nowMillis)
        }
    }

    fun revoke(context: Context) {
        prefs(context).edit {
            putBoolean(KEY_CONFIRMED, false)
            remove(KEY_CONFIRMED_AT_MILLIS)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
