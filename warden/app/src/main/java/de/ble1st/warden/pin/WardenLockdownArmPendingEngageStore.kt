package de.ble1st.warden.pin

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import de.ble1st.warden.R
import de.ble1st.warden.domain.pin.PendingEngageFreshness

/** [requiresConfirmation]: `true` heißt, der Abholpunkt (`de.ble1st.warden.ui
 * .WardenStatusActivity.consumePendingLockdownArm`) muss vor dem tatsächlichen Scharfschalten
 * noch einen Ja/Nein-Dialog zeigen ([de.ble1st.warden.domain.pin.LockdownTriggerProfile.STANDARD]),
 * statt sofort zu feuern — dieselbe Semantik wie [PendingLockTaskEngage]. */
data class PendingLockdownArm(val reason: String, val requiresConfirmation: Boolean)

/**
 * "Quick-Action-Widget für Lockdown/Sentinel-Kiosk" (2026-09-05, Nutzerwunsch) — derselbe
 * Übergabepunkt-Mechanismus wie [WardenLockTaskPendingEngageStore], nur für
 * `SensitiveAction.LOCKDOWN_MODE_ARM` statt `LOCKDOWN_TASK_ENGAGE`. Getrennter Store statt
 * Wiederverwendung desselben: beide Aktionen können unabhängig voneinander ausstehen (ein Tap auf
 * die eine Widget-Schaltfläche darf eine bereits vorgemerkte andere nicht überschreiben).
 *
 * [requestEngage] wird ausschließlich aus [de.ble1st.warden.widget.WardenQuickActionCallbacks]
 * aufgerufen — reines In-Prozess-Schreiben, bevor überhaupt eine `Activity` gestartet wird (s.
 * dessen Klassendoc, warum das sicherheitsrelevant ist: `WardenStatusActivity` ist als Launcher
 * exportiert, ein Intent-Extra allein wäre von außen fälschbar). [consumeIfPending] wird von
 * `WardenStatusActivity.onResume()` aufgerufen, exakt an derselben Stelle wie
 * [WardenLockTaskPendingEngageStore.consumeIfPending] — die nächste Gelegenheit, in der Warden
 * bereits [de.ble1st.warden.presence.WardenLockSession]-authentifiziert im Vordergrund läuft, holt
 * die Anforderung ab. Ohne bereits gültige Session (kalter Start über die Widget-Schaltfläche)
 * durchläuft die App zuerst ganz normal `WardenLockActivity` — dieser Store umgeht also nie den
 * PIN-Zugangsschutz, nur den zweiten, aktions-eigenen Biometrie-/PIN-Schritt (Session-Presence-
 * Wiederverwendung, strukturell ohnehin für `LOCKDOWN_MODE_ARM` erlaubt,
 * `SensitiveAction.allowsSessionPresence`).
 *
 * **Ablaufdatum (2026-09-05, auf Nutzerwunsch nach dem Gerätetest):** eine vorgemerkte Anforderung
 * ist nicht mehr unbegrenzt gültig. `requestEngage` legt `SystemClock.elapsedRealtime()` und das
 * gewünschte Fenster mit ab, `consumeIfPending` verwirft eine abgelaufene Anforderung still (und
 * räumt sie dabei auf, damit sie nicht liegen bleibt) — s. [PendingEngageFreshness] für die
 * Begründung der monotonen Uhr und der zwei verschiedenen Fenster.
 */
object WardenLockdownArmPendingEngageStore {
    private const val PREFS_NAME = "warden_lockdown_arm_pending_engage"
    private const val KEY_PENDING = "pending"
    private const val KEY_REASON = "reason"
    private const val KEY_REQUIRES_CONFIRMATION = "requires_confirmation"
    private const val KEY_REQUESTED_AT = "requested_at_elapsed"
    private const val KEY_VALIDITY = "validity_millis"
    private const val TAG = "PendingLockdownArm"

    /** [validityMillis]: wie lange diese Anforderung abgeholt werden darf. Der Default ist
     * bewusst das **kürzere** der beiden Fenster — ein vergessener Parameter führt so zu einer zu
     * früh verfallenen Anforderung, nicht zu einer zu lange gültigen. S. [PendingEngageFreshness]. */
    fun requestEngage(
        context: Context,
        reason: String,
        requiresConfirmation: Boolean,
        validityMillis: Long = PendingEngageFreshness.INTERACTIVE_VALIDITY_MILLIS,
    ) {
        prefs(context).edit {
            putBoolean(KEY_PENDING, true)
            putString(KEY_REASON, reason)
            putBoolean(KEY_REQUIRES_CONFIRMATION, requiresConfirmation)
            putLong(KEY_REQUESTED_AT, SystemClock.elapsedRealtime())
            putLong(KEY_VALIDITY, validityMillis)
        }
    }

    /** "Lesen == Verbrauchen", derselbe Vertrag wie
     * [WardenLockTaskPendingEngageStore.consumeIfPending]. */
    fun consumeIfPending(context: Context): PendingLockdownArm? {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_PENDING, false)) return null
        val reason = prefs.getString(KEY_REASON, null) ?: context.getString(R.string.lockdown_arm_pending_engage_default_reason)
        val requiresConfirmation = prefs.getBoolean(KEY_REQUIRES_CONFIRMATION, false)
        val stillValid = PendingEngageFreshness.isStillValid(
            requestedAtElapsedMillis = prefs.getLong(KEY_REQUESTED_AT, 0L),
            nowElapsedMillis = SystemClock.elapsedRealtime(),
            validityMillis = prefs.getLong(KEY_VALIDITY, 0L),
        )
        // Aufgeräumt wird in *beiden* Fällen: eine abgelaufene Anforderung muss verschwinden, sonst
        // liegt sie weiter herum und die Ablaufprüfung liefe bei jedem `onResume()` erneut.
        clear(prefs)
        if (!stillValid) {
            // Still verworfen — genau das ist der Zweck. Ein Dialog, dessen Auslöser der Nutzer
            // nicht mehr erinnert, wäre schlechter als gar keiner; die Schaltfläche ist jederzeit
            // erneut antippbar. Die Log-Zeile bleibt, damit der Fall im Gerätetest nachweisbar ist.
            Log.i(TAG, "abgelaufene Anforderung verworfen: $reason")
            return null
        }
        return PendingLockdownArm(reason, requiresConfirmation)
    }

    private fun clear(prefs: SharedPreferences) {
        prefs.edit {
            putBoolean(KEY_PENDING, false)
            remove(KEY_REASON)
            remove(KEY_REQUIRES_CONFIRMATION)
            remove(KEY_REQUESTED_AT)
            remove(KEY_VALIDITY)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
