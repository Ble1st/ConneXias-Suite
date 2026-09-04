package de.ble1st.warden.usb

import android.content.Context
import androidx.core.content.edit

/**
 * Persistiert nur das Ein/Aus für [UsbAutoLockController] — reine lokale Präferenz, kein
 * DPM-Ist-Zustand (der lebt in `DevicePolicyManager.isUsbDataSignalingEnabled` selbst und wird bei
 * jedem [UsbAutoLockController.checkAndSync]-Lauf live abgefragt, nie gecacht). Dieselbe
 * "kein Device-Protected-Storage nötig"-Begründung wie
 * [de.ble1st.warden.autoreboot.AutoRebootStorage]: die Funktion beobachtet den Sperrzustand
 * periodisch über WorkManager, das vor dem ersten Entsperren nach einem Boot ohnehin nicht
 * zuverlässig läuft.
 */
object UsbAutoLockStorage {
    private const val PREFS_NAME = "warden_usb_auto_lock"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
