package de.ble1st.warden.registry

import android.app.admin.DevicePolicyManager
import android.content.Context

/**
 * Meilenstein H.8 (Konzept Abschnitt 7: "Drei Ebenen: `setLockTaskPackages()` /
 * `startLockTask()`+`stopLockTask()` / `setLockTaskFeatures()`"). Warden-seitige DPM-Anbindung —
 * nur der Device Owner darf ein Paket für Lock-Task-Modus whitelisten. Passt strukturell ins
 * bestehende [Safeguard][de.ble1st.warden.domain.registry.Safeguard]-Muster (`DpmSafeguard`-Basis
 * wie `CameraSafeguard` etc.), wird aber bewusst **nirgends registriert**
 * (`DeviceLockdownBundle`, `RegistryReconciliationReceiver`, ...) — s. u.
 *
 * Anders als im ConneXias-Framework-Quellprojekt (dort: Warden whitelistet das *fremde*
 * Sentinel-Paket, `de.ble1st.connexiassentinel`) whitelistet dieser Safeguard **Wardens eigenes
 * Paket** — Sentinels PIN-/Lock-Task-Logik läuft jetzt in Wardens eigenem Prozess (s.
 * Plan-Abschnitt "Presence: Sentinels PIN-Logik portiert"), es gibt kein fremdes Paket mehr, das
 * autorisiert werden müsste.
 *
 * **Warum dies live testbar ist, `startLockTask()` selbst (`WardenLockTaskManager`) aber nicht:**
 * [apply]/[revert] autorisieren nur, *dass* das eigene Paket *überhaupt* in den Lock-Task-Modus
 * wechseln *darf* — sie versetzen das Gerät selbst nicht in diesen Modus und sind jederzeit ohne
 * Risiko wieder entfernbar. Das eigentliche Risiko fürs Testgerät liegt einzig in
 * `Activity.startLockTask()` selbst — dort, nicht hier, greift dieselbe Vorsicht wie im
 * Quellprojekt ("bauen, aber nicht live scharf schalten").
 */
class WardenLockTaskAuthorizer(private val ctx: Context) : DpmSafeguard(ctx) {

    override val id: String = ID

    /** Whitelisted Wardens eigenes Paket für Lock-Task-Modus + setzt die Feature-Bitmaske auf
     * "alles außer den Feature-Flags, die den Notrufpfad tragen, entzogen" (Konzept 7: "Maximal-
     * Härte ist 'alles entzogen **außer** dem Feature, das den Notrufpfad trägt', nicht
     * `LOCK_TASK_FEATURE_NONE`"). */
    override fun apply() {
        val dpm = devicePolicyManager()
        dpm.setLockTaskPackages(admin, arrayOf(ctx.packageName))
        dpm.setLockTaskFeatures(admin, EMERGENCY_PRESERVING_FEATURES)
    }

    /** Entfernt die Whitelist wieder — `startLockTask()` kann danach gar nicht mehr erfolgreich
     * aufgerufen werden. */
    override fun revert() {
        val dpm = devicePolicyManager()
        dpm.setLockTaskPackages(admin, emptyArray())
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
    }

    override fun isActive(): Boolean =
        ctx.packageName in devicePolicyManager().getLockTaskPackages(admin)

    companion object {
        const val ID = "warden_lock_task_authorized"

        /** Konzept 7: `LOCK_TASK_FEATURE_KEYGUARD`/`LOCK_TASK_FEATURE_GLOBAL_ACTIONS` bleiben
         * offen (tragen den System-Notrufpfad), alles andere (Home, Overview, Notifications, ...)
         * ist durch bloßes Weglassen aus der Bitmaske entzogen. */
        const val EMERGENCY_PRESERVING_FEATURES =
            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
    }
}
