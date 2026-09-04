package de.ble1st.warden.tracker

import android.content.Context
import androidx.core.content.edit

/**
 * App-Präferenz für [BleTrackerController] (2026-09-03) — **bewusst standardmäßig aus**, dieselbe
 * Zurückhaltung wie [de.ble1st.warden.clipboard.ClipboardGuardStorage]/[de.ble1st.warden.usb
 * .UsbAutoLockStorage]: periodisches BLE-Scannen kostet spürbar mehr Akku als die übrigen
 * 15-Minuten-Worker in diesem Projekt (aktiver Funk-Scan statt reinem Lesezugriff auf bereits
 * vorhandene Systemzustände), ein Opt-in ist hier angemessener als ein Standard-an.
 */
object TrackerGuardStorage {
    private const val PREFS_NAME = "warden_tracker_guard"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
