package de.ble1st.warden.registry

import android.content.Context

/**
 * Eigene Idee, ergänzend zu Tier 4 ("Passwort-/Sperrbildschirm-Policy", 2026-08-22), zusammen mit
 * [PasswordComplexitySafeguard]: erzwingt eine kurze Auto-Sperrzeit über
 * `DevicePolicyManager.setMaximumTimeToLock`/`getMaximumTimeToLock` — ein starker Sperrbildschirm-
 * PIN ([PasswordComplexitySafeguard]) nützt wenig, wenn das Gerät minutenlang entsperrt offen
 * liegen bleibt, bevor es überhaupt sperrt.
 *
 * `apply()` setzt [MAX_TIME_TO_LOCK_MILLIS] (30 Sekunden); `revert()` setzt `0` — laut
 * Android-API bedeutet `0` "keine Einschränkung", nicht "sofort sperren".
 */
class AutoLockTimeoutSafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        devicePolicyManager().setMaximumTimeToLock(admin, MAX_TIME_TO_LOCK_MILLIS)
    }

    override fun revert() {
        devicePolicyManager().setMaximumTimeToLock(admin, 0L)
    }

    override fun isActive(): Boolean {
        val current = devicePolicyManager().getMaximumTimeToLock(admin)
        return current in 1..MAX_TIME_TO_LOCK_MILLIS
    }

    companion object {
        const val ID = "auto_lock_timeout"
        private const val MAX_TIME_TO_LOCK_MILLIS = 30_000L
    }
}
