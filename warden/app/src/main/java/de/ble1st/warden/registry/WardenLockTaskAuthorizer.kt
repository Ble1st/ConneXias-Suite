package de.ble1st.warden.registry

import android.app.admin.DevicePolicyManager
import android.content.Context

/**
 * Meilenstein H.8 (Konzept Abschnitt 7: "Drei Ebenen: `setLockTaskPackages()` /
 * `startLockTask()`+`stopLockTask()` / `setLockTaskFeatures()`"). Warden-seitige DPM-Anbindung —
 * nur der Device Owner darf ein Paket für Lock-Task-Modus whitelisten. Passt strukturell ins
 * bestehende [Safeguard][de.ble1st.warden.domain.registry.Safeguard]-Muster (`DpmSafeguard`-Basis
 * wie `CameraSafeguard` etc.) und ist Mitglied von [DeviceLockdownBundle] — **nicht** aber von
 * `RegistryReconciliationReceiver`s Katalog (kein automatisches Wieder-Scharfschalten der
 * Lock-Task-Whitelist nach Boot, dieselbe bewusste Auslassung wie beim Rest des Bündels, s.
 * [DeviceLockdownBundle]-Klassendoc) — s. u.
 *
 * **Seit "Sentinel: eigenständige Kiosk-PIN-App" whitelistet dies wieder das *fremde* Sentinel-
 * Paket** (`de.ble1st.warden.sentinel`, dasselbe Signing-Zertifikat wie Warden) — wie im
 * ursprünglichen ConneXias-Framework-Quellprojekt, nicht mehr Wardens eigenes Paket: der
 * eingesperrte Kiosk-Task ist jetzt Sentinels einziger PIN-Bildschirm statt Wardens gesamter
 * Dashboard-UI, s. `de.ble1st.warden.presence.SentinelLockdownEngager`-Klassendoc für die volle
 * Begründung.
 *
 * **Warum dies live testbar ist, `startLockTask()` selbst (in Sentinels eigenem Prozess) aber
 * nicht:** [apply]/[revert] autorisieren nur, *dass* Sentinels Paket *überhaupt* in den
 * Lock-Task-Modus wechseln *darf* — sie versetzen das Gerät selbst nicht in diesen Modus und sind
 * jederzeit ohne Risiko wieder entfernbar. Das eigentliche Risiko fürs Testgerät liegt einzig in
 * `Activity.startLockTask()` selbst.
 */
class WardenLockTaskAuthorizer(private val ctx: Context) : DpmSafeguard(ctx) {

    override val id: String = ID

    /** Whitelisted Sentinels Paket für Lock-Task-Modus + setzt die Feature-Bitmaske auf "alles
     * außer den Feature-Flags, die den Notrufpfad tragen, entzogen" (Konzept 7: "Maximal-Härte
     * ist 'alles entzogen **außer** dem Feature, das den Notrufpfad trägt', nicht
     * `LOCK_TASK_FEATURE_NONE`"). */
    override fun apply() {
        val dpm = devicePolicyManager()
        dpm.setLockTaskPackages(admin, arrayOf(SENTINEL_PACKAGE_NAME))
        dpm.setLockTaskFeatures(admin, EMERGENCY_PRESERVING_FEATURES)
    }

    /** Entfernt die Whitelist wieder — `startLockTask()` kann danach gar nicht mehr erfolgreich
     * aufgerufen werden. */
    override fun revert() {
        val dpm = devicePolicyManager()
        dpm.setLockTaskPackages(admin, emptyArray())
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
    }

    /** analyse.md (2. Durchgang, Mittel — "isActive() prüft nicht, was apply() setzt"): vorher nur
     * die Paket-Whitelist geprüft, nicht die Feature-Bitmaske, die [apply] als zweiten,
     * gleichberechtigten Teil setzt. Ein Zustand, in dem Sentinel zwar whitelisted ist, die
     * Feature-Maske aber (noch/wieder) offen steht — z. B. nach einem fehlgeschlagenen [apply], das
     * `setLockTaskPackages` erfolgreich, `setLockTaskFeatures` aber nicht durchbrachte — hätte
     * bisher trotzdem "aktiv/autorisiert" gemeldet, obwohl Home/Overview im Kiosk-Modus wieder
     * erreichbar wären. Jetzt werden beide DPM-Bits geprüft, exakt wie [apply] sie setzt. */
    override fun isActive(): Boolean {
        val dpm = devicePolicyManager()
        return SENTINEL_PACKAGE_NAME in dpm.getLockTaskPackages(admin) &&
            dpm.getLockTaskFeatures(admin) == EMERGENCY_PRESERVING_FEATURES
    }

    companion object {
        const val ID = "warden_lock_task_authorized"

        /** `de.ble1st.warden.sentinelbridge`-Paket referenziert dies als String-Literal statt per
         * Import — bewusst kein Compile-Zeit-Bezug auf das `:sentinel`-Modul von `:app` aus
         * (dieselbe Grenze wie im Quellprojekt: `:core:data` hing nie von `:sentinel` ab). */
        const val SENTINEL_PACKAGE_NAME = "de.ble1st.warden.sentinel"

        /** Konzept 7: `LOCK_TASK_FEATURE_KEYGUARD`/`LOCK_TASK_FEATURE_GLOBAL_ACTIONS` bleiben
         * offen (tragen den System-Notrufpfad, empirisch auf dem A15 im ConneXias-Framework-
         * Quellprojekt bestätigt — s. `docs/D4-wiederaufbau-runbook.md` Schritt 6 dort), alles
         * andere (Home, Overview, Notifications, ...) ist durch bloßes Weglassen aus der
         * Bitmaske entzogen. */
        const val EMERGENCY_PRESERVING_FEATURES =
            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
    }
}
