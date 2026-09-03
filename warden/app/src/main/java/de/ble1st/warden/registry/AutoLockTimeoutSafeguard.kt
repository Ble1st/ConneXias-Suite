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

    /** analyse.md (2. Durchgang, Niedrig — "isActive() akzeptiert jeden Wert 1–30000 ms"): vorher
     * jeder Wert im gesamten Bereich als "aktiv" gewertet, nicht nur exakt [MAX_TIME_TO_LOCK_MILLIS]
     * selbst, das [apply] setzt — ein z. B. OEM-seitig auf 15 Sekunden voreingestellter Wert (auch
     * strenger als Wardens eigene 30-Sekunden-Vorgabe) galt damit als "aktiv", ohne dass
     * [RegistryReconciler] je nachgefasst hätte, sollte sich dieser Fremd-Wert später wieder
     * lockern. Exakter Vergleich statt Bereichsprüfung — derselbe "isActive() prüft, was apply()
     * tatsächlich setzt"-Grundsatz wie bei [de.ble1st.warden.netlock.NetLockdownAuthorizer]/
     * [WardenLockTaskAuthorizer]. */
    override fun isActive(): Boolean =
        devicePolicyManager().getMaximumTimeToLock(admin) == MAX_TIME_TO_LOCK_MILLIS

    companion object {
        const val ID = "auto_lock_timeout"
        private const val MAX_TIME_TO_LOCK_MILLIS = 30_000L
    }
}
