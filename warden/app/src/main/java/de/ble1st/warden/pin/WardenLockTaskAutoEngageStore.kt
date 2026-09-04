package de.ble1st.warden.pin

import android.content.Context
import androidx.core.content.edit

/**
 * "LockMode/Threat-Protection-Ausbau" (2026-08-25) — eigener, dritter Opt-in neben
 * [WardenLockTaskDrillStorage] (Drill bestätigt) und `DeviceLockdownBundle`/
 * `SensitiveAction.LOCKDOWN_MODE_ARM` (Lockdown scharf): ob ein *kritischer* Verdachtsfund
 * (`de.ble1st.warden.domain.appmanagement.ThreatSeverity.CRITICAL`, s.
 * `WardenLockTaskAutoEngageDecision`) automatisch — ohne weiteren Tap — ein Lock-Task-Engage
 * anfordern darf. Default `false`, dieselbe "still + wirkmächtig => nur explizit"-Haltung wie
 * [de.ble1st.warden.appmanagement.SuspiciousAppScanStore.isEnabled] (Auto-Einfrieren).
 *
 * **Bewusst getrennt vom Lockdown-Scharfschalten selbst:** wer `LOCKDOWN_MODE_ARM` presence-gated
 * bestätigt, hat damit noch nicht zugestimmt, dass Warden zusätzlich *unbeaufsichtigt* in den
 * Lock-Task-Modus wechselt, sobald irgendein Scan-Lauf einen kritischen Fund meldet — das ist eine
 * eigene, weitergehende Entscheidung mit eigenem Schalter.
 */
object WardenLockTaskAutoEngageStore {
    private const val PREFS_NAME = "warden_lock_task_auto_engage"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, enabled) }
    }
}
