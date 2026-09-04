package de.ble1st.warden.pin

import android.content.Context
import de.ble1st.warden.domain.pin.LockdownTriggerProfile
import de.ble1st.warden.domain.pin.WardenLockTaskDrillFreshnessDecision

/**
 * "Lockdown-Auslöse-Profil" (2026-08-27) — der einzige Ort, an dem [WardenLockTaskDrillStorage]s
 * rohes `isConfirmed()`/`confirmedAtMillis()` mit dem aktiven
 * [de.ble1st.warden.domain.pin.LockdownTriggerProfile] kombiniert wird, bevor der Wert als
 * `emergencyCallDrillPassed` an `de.ble1st.warden.sentinelbridge.SentinelLockdownEngager
 * .engage()` weitergereicht wird. Ersetzt an allen Aufrufstellen
 * (`de.ble1st.warden.presence.SensitiveActionActivity`, `de.ble1st.warden.ui.WardenStatusActivity`,
 * dem neuen Dashboard-Button dort) das vorherige direkte
 * `WardenLockTaskDrillStorage.isConfirmed(context)`.
 *
 * Nur unter [LockdownTriggerProfile.STRICT] weicht das Ergebnis von der reinen
 * `isConfirmed()`-Antwort ab (Ablauf nach [WardenLockTaskDrillFreshnessDecision
 * .DEFAULT_MAX_AGE_MILLIS]) — STANDARD/FAST bleiben exakt beim bisherigen "einmal bestätigt, für
 * immer gültig"-Verhalten.
 */
object WardenLockTaskDrillFreshnessGate {
    fun effectiveEmergencyCallDrillPassed(context: Context): Boolean {
        if (!WardenLockTaskDrillStorage.isConfirmed(context)) return false
        if (LockdownTriggerProfileStore.load(context) != LockdownTriggerProfile.STRICT) return true
        return WardenLockTaskDrillFreshnessDecision.isFresh(
            confirmedAtMillis = WardenLockTaskDrillStorage.confirmedAtMillis(context),
            nowMillis = System.currentTimeMillis(),
        )
    }
}
