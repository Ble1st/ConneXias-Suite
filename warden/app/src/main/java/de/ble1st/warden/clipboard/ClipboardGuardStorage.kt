package de.ble1st.warden.clipboard

import android.content.Context
import androidx.core.content.edit

/**
 * Persistiert nur die App-Präferenz für [ClipboardGuardController] — reine lokale Einstellung,
 * kein DPM-Ist-Zustand (den gibt es hier ohnehin nicht, s. `docs/design-clipboard-guard.md`
 * Abschnitt 1: Android hat keine `DevicePolicyManager`-API für die Zwischenablage). Gleiche
 * "kein Device-Protected-Storage nötig"-Begründung wie [de.ble1st.warden.usb.UsbAutoLockStorage]:
 * die Funktion beobachtet Wardens eigene Fokus-Momente (Dashboard-Öffnen, manueller Button), die
 * vor dem ersten Entsperren nach einem Boot ohnehin nicht auftreten können.
 *
 * **Bewusst standardmäßig aus** — dieselbe Zurückhaltung wie bei [de.ble1st.warden.usb
 * .UsbAutoLockStorage]: ungefragtes Leeren der Zwischenablage kann absichtlich kopierten,
 * gerade noch gebrauchten Inhalt zerstören, ist also kein Verhalten, das man stillschweigend
 * einschalten sollte.
 *
 * [ThresholdMillis] ist bewusst konfigurierbar (nicht nur hart codiert), auch wenn diese erste
 * Iteration noch keinen eigenen UI-Regler dafür anbietet (`docs/design-clipboard-guard.md`
 * Abschnitt 5, offene Frage 1) — Default 0 (sofort leeren bei jeder Fokus-Gelegenheit), der
 * simpelste und für den Nutzer vorhersagbarste Wert, solange die Funktion ohnehin nur nach
 * explizitem Opt-in aktiv wird.
 */
object ClipboardGuardStorage {
    private const val PREFS_NAME = "warden_clipboard_guard"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_THRESHOLD_MILLIS = "threshold_millis"
    private const val KEY_LAST_CLEAR_AT = "last_clear_at"
    private const val KEY_CROSS_APP_MONITORING_ENABLED = "cross_app_monitoring_enabled"

    const val DEFAULT_THRESHOLD_MILLIS = 0L

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun thresholdMillis(context: Context): Long =
        prefs(context).getLong(KEY_THRESHOLD_MILLIS, DEFAULT_THRESHOLD_MILLIS)

    fun setThresholdMillis(context: Context, millis: Long) {
        prefs(context).edit { putLong(KEY_THRESHOLD_MILLIS, millis.coerceAtLeast(0L)) }
    }

    /** `null`, solange noch nie geleert wurde — für die StatusCard-Zeile, s.
     * [de.ble1st.warden.clipboard.ClipboardGuardController]. */
    fun lastClearAt(context: Context): Long? =
        prefs(context).getLong(KEY_LAST_CLEAR_AT, -1L).takeIf { it >= 0L }

    fun recordClearNow(context: Context, atMillis: Long) {
        prefs(context).edit { putLong(KEY_LAST_CLEAR_AT, atMillis) }
    }

    /**
     * Eigene Präferenz für Phase 2 (`docs/design-clipboard-guard.md` Abschnitt 3.2.7), unabhängig
     * von [isEnabled] (Phase 1, fokusgebundenes Auto-Clear) — beide lassen sich unabhängig
     * voneinander schalten, Phase 2 setzt Phase 1 nicht voraus. **Bewusst standardmäßig aus**,
     * genau wie [isEnabled] — hier umso mehr, da diese Einstellung zusätzlich eine manuelle
     * Bedienungshilfen-Freigabe außerhalb der App voraussetzt (s.
     * [de.ble1st.warden.clipboard.ClipboardAccessibilityStatus]); dieser Schalter allein aktiviert
     * noch keinen Systemzugriff, er ist die App-seitige Hälfte des Opt-in
     * ([de.ble1st.warden.domain.clipboard.ClipboardAccessDecision] prüft ihn als ersten Filter).
     */
    fun isCrossAppMonitoringEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CROSS_APP_MONITORING_ENABLED, false)

    fun setCrossAppMonitoringEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_CROSS_APP_MONITORING_ENABLED, enabled) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
