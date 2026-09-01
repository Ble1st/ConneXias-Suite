package de.ble1st.warden.performance

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Ein Batterie-Snapshot ("Lokale Statistik-Erfassung via BatteryManager", 2026-08-25).
 * `percent` ist bereits auf 0..100 normiert (`level`/`scale` je nach Gerät nicht immer 0..100). */
data class BatterySnapshot(
    val percent: Int,
    val charging: Boolean,
    val temperatureCelsius: Float?,
    val voltageMillivolts: Int?,
)

/**
 * Performance-Monitoring-Fenster (2026-08-25). `Context.registerReceiver(null, filter)` liest den
 * zuletzt gestickten `ACTION_BATTERY_CHANGED`-Broadcast synchron zurück, ohne einen echten
 * Empfänger zu registrieren — Androids dokumentierter Weg, den aktuellen Batteriezustand ohne
 * einen dauerhaft laufenden `BroadcastReceiver` abzufragen (dasselbe Muster, das Android selbst
 * z. B. für `BatteryManager`-Beispielcode empfiehlt).
 */
class BatteryStatusReader(private val context: Context) {

    fun read(): BatterySnapshot? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val tenthsOfCelsius = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
        return BatterySnapshot(
            percent = (level * 100) / scale,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            temperatureCelsius = if (tenthsOfCelsius == Int.MIN_VALUE) null else tenthsOfCelsius / 10f,
            voltageMillivolts = if (voltage == Int.MIN_VALUE) null else voltage,
        )
    }
}
