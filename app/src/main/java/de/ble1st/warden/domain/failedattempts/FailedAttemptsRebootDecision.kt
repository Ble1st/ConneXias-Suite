package de.ble1st.warden.domain.failedattempts

/**
 * "Neustart nach zu vielen Fehlversuchen" (2026-08-28, auf Nutzerwunsch aus der Lückenanalyse) —
 * reine Entscheidungslogik, dieselbe Trennung wie [de.ble1st.warden.domain.autoreboot
 * .AutoRebootDecision]: kennt weder `DevicePolicyManager` noch `SharedPreferences`, der
 * [de.ble1st.warden.failedattempts.FailedAttemptsRebootController] liest die echten Werte und
 * reicht sie fertig herein.
 *
 * **Bewusst Neustart statt Wipe.** Die ursprünglich geplante Variante hätte
 * `DevicePolicyManager.setMaximumFailedPasswordsForWipe` benutzt — das löscht das Gerät
 * OS-seitig unwiderruflich, und zwar auch dann, wenn ein Kind, eine Hosentasche oder ein
 * verwirrter Gast die Fehlversuche produziert hat. Ein `reboot()` erreicht das eigentliche
 * Schutzziel (BFU: der FBE-Schlüssel ist nicht mehr im RAM, Biometrie und Trust Agents sind
 * gesperrt, nur die echten Zugangsdaten entsperren wieder) **ohne** unumkehrbaren Datenverlust —
 * dieselbe Abwägung, die schon [de.ble1st.warden.presence.DuressPinResponder] und
 * [de.ble1st.warden.autoreboot.AutoRebootController] treffen, und der Grund, warum
 * `SensitiveAction.WIPE_DATA` in diesem Projekt ein Stub bleibt.
 *
 * Zählt die Fehlversuche am **System-Sperrbildschirm** (`onPasswordFailed` des
 * DeviceAdminReceivers, `watch-login`-Policy) — nicht zu verwechseln mit
 * [de.ble1st.warden.domain.pin.WardenAntiHammeringDecision], das ausschließlich Wardens eigene
 * In-App-PIN drosselt. Beide Zähler sind unabhängig und sollen es bleiben.
 */
object FailedAttemptsRebootDecision {

    /** Unterhalb von drei Versuchen wäre jede Fehleingabe im Alltag ein Neustart — kein sinnvoller
     * Schwellwert, deshalb die Untergrenze der UI-Presets. */
    const val MIN_THRESHOLD = 3

    /** Großzügige Obergrenze gegen Fehleingaben, dieselbe Rolle wie
     * [de.ble1st.warden.autoreboot.AutoRebootStorage.MAX_THRESHOLD_HOURS]. */
    const val MAX_THRESHOLD = 20

    /**
     * @param threshold Soll-Anzahl aufeinanderfolgender Fehlversuche; `null`/`<= 0` = Funktion aus.
     * @param failedAttempts bisher gezählte aufeinanderfolgende Fehlversuche **einschließlich** des
     * gerade gemeldeten (der Aufrufer erhöht vor dem Auswerten).
     */
    fun shouldReboot(threshold: Int?, failedAttempts: Int): Boolean {
        if (threshold == null || threshold <= 0) return false
        if (failedAttempts <= 0) return false
        return failedAttempts >= threshold
    }
}
