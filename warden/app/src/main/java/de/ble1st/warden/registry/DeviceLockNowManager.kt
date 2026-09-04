package de.ble1st.warden.registry

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build

/**
 * Wraps `DevicePolicyManager.lockNow()` — extrahiert aus [de.ble1st.warden.presence
 * .SensitiveActionActivity] (2026-08-22, "LOCK_NOW als Device Command"), damit dieselbe
 * Eviction-Flag-Logik von zwei unabhängigen Aufrufstellen genutzt werden kann, ohne sie zu
 * duplizieren:
 * - dem presence-gated `SensitiveAction.LOCK_NOW`-Pfad (Bestätigungstext + Biometrie/PIN nötig),
 * - einem neuen, niedrigschwelligen "Jetzt sperren"-Menüpunkt direkt auf dem Dashboard
 *   ([de.ble1st.warden.bus.ConcordBus.lockNow]) — bewusst **ohne** Presence-Gate: Sperren ist im
 *   Gegensatz zu `REBOOT`/`WIPE_DATA`/`MASTER_SWITCH_REVERT` niemals im Interesse eines
 *   Angreifers, der das entsperrte Gerät gerade in der Hand hat (es macht die Situation für die
 *   Besitzerin nur sicherer, nie schlimmer) — dieselbe Risikoeinstufung wie die reversiblen
 *   Safeguard-Umschalter, kein zusätzlicher Bestätigungsschritt nötig.
 *
 * `FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY` (API 26+) erzwingt eine vollständige Passwort-/PIN-
 * Eingabe statt eines schnellen Biometrie-/Trust-Agent-Unlocks, unabhängig davon, ob
 * [KeyguardHardeningSafeguard] gerade aktiv ist — Fallback auf einfaches `lockNow()` darunter.
 * `lockNow()` verlangt **kein** `ComponentName admin` (anders als die meisten übrigen DPM-Aufrufe
 * in diesem Projekt) — Android leitet den Device-Owner-Aufrufer implizit her.
 */
class DeviceLockNowManager(context: Context) {
    private val dpm = checkNotNull(context.getSystemService(DevicePolicyManager::class.java)) {
        "DevicePolicyManager nicht verfügbar"
    }

    fun lockNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dpm.lockNow(DevicePolicyManager.FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY)
        } else {
            dpm.lockNow()
        }
    }
}
