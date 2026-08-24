package de.ble1st.warden.domain.autoreboot

/**
 * Reine Entscheidungslogik für "Auto-Reboot nach Zeitfenster ohne Entsperren" (2026-08-22, auf
 * Nutzerwunsch: "ein Zeitfenster für Autoreboot nach vergangener Zeit nach letztem Entsperren des
 * Geräts") — dieselbe Trennung wie bei `SensitiveActionDecision`/`FailsafeDecision`: [evaluate]
 * kennt weder `KeyguardManager` noch `DevicePolicyManager`/`SharedPreferences`
 * ([de.ble1st.warden.autoreboot.AutoRebootController] wertet die echten Werte aus und reicht sie
 * hier als bereits gelesene Werte herein), framework-frei, ohne Android-Fakes testbar.
 *
 * Sicherheitshärtung, kein Diebstahl-Ortungsfeature (kein Standort/Fernzugriff, s. bisherige
 * Tier-1-6-Härtungsrunden dieser Session): ein Gerät, das lange gesperrt und ungenutzt herumliegt
 * (verloren, beschlagnahmt, gestohlen), rebootet automatisch zurück in den BFU-Zustand
 * (Before-First-Unlock) — Biometrie/Trust Agents sind dort nicht verfügbar, nur die eigentliche
 * Zugangsdaten-Eingabe entsperrt wieder, und der FBE-Schlüssel ist nicht mehr im RAM.
 */
object AutoRebootDecision {

    /**
     * @param isLockedNow aktueller Sperrzustand (`KeyguardManager.isKeyguardLocked`), live zum
     * Zeitpunkt der Prüfung.
     * @param lastSeenUnlockedMillis wann der periodische Check das Gerät zuletzt entsperrt vorfand
     * (`null` = noch nie beobachtet, z. B. direkt nach Aktivieren dieser Funktion — s.
     * [de.ble1st.warden.autoreboot.AutoRebootController]-Klassendoc, warum das beim Aktivieren
     * sofort mit dem aktuellen Zeitpunkt vorbelegt wird, damit dieser Fall in der Praxis nicht
     * auftritt).
     * @param nowMillis aktuelle Zeit zum Zeitpunkt der Prüfung.
     * @param thresholdMillis Soll-Zeitfenster aus den Einstellungen; `null`/`<= 0` = Funktion
     * deaktiviert.
     */
    fun shouldReboot(
        isLockedNow: Boolean,
        lastSeenUnlockedMillis: Long?,
        nowMillis: Long,
        thresholdMillis: Long?,
    ): Boolean {
        if (thresholdMillis == null || thresholdMillis <= 0) return false
        if (!isLockedNow) return false
        // Unbekannte Baseline -> bewusst NICHT rebooten (Fail-Safe hier heißt: kein überraschender
        // Reboot ohne belastbare Grundlage, nicht "im Zweifel härter"; s. Controller-Klassendoc,
        // warum dieser Fall praktisch nie eintritt).
        val lastUnlocked = lastSeenUnlockedMillis ?: return false
        return nowMillis - lastUnlocked >= thresholdMillis
    }
}
