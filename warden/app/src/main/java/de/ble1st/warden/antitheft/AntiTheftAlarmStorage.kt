package de.ble1st.warden.antitheft

import android.content.Context
import androidx.core.content.edit
import de.ble1st.warden.domain.antitheft.AntiTheftConfig

/**
 * Soll-Zustand für [AntiTheftAlarmController]/[AntiTheftLockStateReceiver]/[AntiTheftMotionMonitor]
 * (2026-09-03) — Device-Protected-Storage aus demselben Grund wie
 * [de.ble1st.warden.cellsecurity.CellSecurityStorage]: ein Ladekabel-Abzieh-Ereignis kann
 * unmittelbar nach einem Neustart auftreten, bevor das Gerät je entsperrt wurde.
 */
object AntiTheftAlarmStorage {
    private const val PREFS_NAME = "warden_anti_theft"
    private const val KEY_MOTION_ENABLED = "motion_alarm_enabled"
    private const val KEY_CHARGER_ENABLED = "charger_alarm_enabled"

    fun load(context: Context): AntiTheftConfig = AntiTheftConfig(
        motionAlarmEnabled = prefs(context).getBoolean(KEY_MOTION_ENABLED, false),
        chargerAlarmEnabled = prefs(context).getBoolean(KEY_CHARGER_ENABLED, false),
    )

    fun setMotionAlarmEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_MOTION_ENABLED, enabled) }
    }

    fun setChargerAlarmEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_CHARGER_ENABLED, enabled) }
    }

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
