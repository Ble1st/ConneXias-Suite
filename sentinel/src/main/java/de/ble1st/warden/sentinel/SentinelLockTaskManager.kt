package de.ble1st.warden.sentinel

import android.app.Activity
import de.ble1st.warden.sentinel.domain.SentinelLockTaskGate

/**
 * Dünner Wrapper um `Activity.startLockTask()`/`stopLockTask()` — funktioniert nur, wenn Warden
 * zuvor `WardenLockTaskAuthorizer.apply()` (`:app`) aufgerufen und Sentinels Paket für Lock-Task
 * whitelistet hat; Sentinel selbst hat keine DPM-Rechte, kann sich nicht selbst autorisieren.
 * [SentinelLockTaskGate] verweigert [startIfPermitted] strukturell, solange
 * `emergencyCallDrillPassed` nicht `true` ist — dieser Wert kommt bei jedem Aufruf frisch von
 * Warden (`SentinelActivity`s `EXTRA_EMERGENCY_CALL_DRILL_PASSED`-Extra), nie lokal gespeichert.
 */
class SentinelLockTaskManager(private val activity: Activity) {

    fun startIfPermitted(emergencyCallDrillPassed: Boolean): Boolean {
        if (!SentinelLockTaskGate.isLockTaskPermitted(emergencyCallDrillPassed)) return false
        activity.startLockTask()
        return true
    }

    fun stop() {
        activity.stopLockTask()
    }
}
