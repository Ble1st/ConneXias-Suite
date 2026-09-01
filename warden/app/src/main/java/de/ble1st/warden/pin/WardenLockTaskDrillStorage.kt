package de.ble1st.warden.pin

import android.content.Context
import androidx.core.content.edit

/**
 * "LockMode/Threat-Protection-Ausbau" (2026-08-25, auf Nutzerwunsch, nach ausdrücklicher Klärung
 * per Rückfrage: "Persistente Bestätigung + Trigger"). Persistiert genau das eine Bit, das seit
 * "Sentinel: eigenständige Kiosk-PIN-App" bei jedem
 * [de.ble1st.warden.sentinelbridge.SentinelLockdownEngager.engage]-Aufruf als
 * `emergencyCallDrillPassed`-Extra an Sentinel weitergereicht wird — Sentinels eigenes
 * `de.ble1st.warden.sentinel.domain.SentinelLockTaskGate` verlangt diesen Wert vom Aufrufer
 * explizit (kein lokal gespeichertes/implizites Flag auf Sentinels Seite, das versehentlich
 * `true` bleiben könnte), Warden ist hier die einzig zulässige Quelle der Wahrheit dafür.
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
 * er sitzt beim tatsächlichen Scharfschalten (`SentinelLockdownEngager.engage`/
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
