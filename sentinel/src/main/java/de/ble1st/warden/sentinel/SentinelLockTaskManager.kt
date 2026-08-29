package de.ble1st.warden.sentinel

import android.app.Activity
import de.ble1st.warden.sentinel.domain.SentinelLockTaskGate

/**
 * Dünner Wrapper um `Activity.startLockTask()`/`stopLockTask()` — funktioniert nur, wenn Warden
 * zuvor `WardenLockTaskAuthorizer.apply()` (`:app`) aufgerufen und Sentinels Paket für Lock-Task
 * whitelistet hat; Sentinel selbst hat keine DPM-Rechte, kann sich nicht selbst autorisieren.
 * [SentinelLockTaskGate] verweigert [startIfPermitted] strukturell, solange nicht **beide**
 * Bedingungen gelten: `emergencyCallDrillPassed` (kommt bei jedem Aufruf frisch von Warden über
 * `SentinelActivity`s `EXTRA_EMERGENCY_CALL_DRILL_PASSED`-Extra, nie lokal gespeichert) und
 * `pinConfigured` (nur Sentinel selbst kann das lesen, s. Gate-Klassendoc).
 */
class SentinelLockTaskManager(private val activity: Activity) {

    fun startIfPermitted(emergencyCallDrillPassed: Boolean, pinConfigured: Boolean): Boolean {
        if (!SentinelLockTaskGate.isLockTaskPermitted(emergencyCallDrillPassed, pinConfigured)) return false
        activity.startLockTask()
        return true
    }

    fun stop() {
        activity.stopLockTask()
    }
}
