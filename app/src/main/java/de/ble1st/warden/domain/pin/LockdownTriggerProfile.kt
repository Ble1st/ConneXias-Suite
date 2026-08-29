package de.ble1st.warden.domain.pin

/**
 * "Lockdown-Auslöse-Profil" (2026-08-27) — steuert, wie schnell/leichtgewichtig
 * `de.ble1st.warden.domain.presence.SensitiveAction.LOCKDOWN_TASK_ENGAGE` ausgelöst werden darf,
 * über die zwei Schnell-Einstiege (Dashboard-Button "Kiosk jetzt" in
 * `de.ble1st.warden.ui.WardenStatusActivity`, Quick-Settings-Kachel
 * `de.ble1st.warden.sentinelbridge.SentinelQuickTile`) UND innerhalb des bestehenden
 * `de.ble1st.warden.presence.SensitiveActionActivity`-Flows selbst. Ändert **niemals**
 * `SensitiveAction.allowsSessionPresence` selbst (das bleibt strukturell für alle Aufrufer
 * gleich) — jede Verhaltensänderung läuft über [LockdownTriggerProfilePolicy], lokal an der
 * jeweiligen Aufrufstelle ausgewertet, nie am Enum.
 */
enum class LockdownTriggerProfile {
    /** Kein Schnellauslöser: Dashboard-Button bleibt ausgeblendet, die Kachel degradiert zu
     * einem reinen Shortcut in [de.ble1st.warden.presence.SensitiveActionActivity]. Dort
     * zusätzlich erzwungener Biometrie-/PIN-Zweiwegepfad + Kühlzeit, selbst wenn
     * `allowsSessionPresence` strukturell `true` bleibt. */
    STRICT,

    /** Schnellauslöser sichtbar/aktiv, aber mit einem Ja/Nein-Bestätigungsdialog unmittelbar vor
     * dem tatsächlichen Scharfschalten. */
    STANDARD,

    /** Schnellauslöser feuert sofort, ohne Rückfrage (haptisches Feedback statt Dialog) — läuft
     * weiterhin unbedingt durch `de.ble1st.warden.domain.presence.DestructiveCommandGuard`. */
    FAST,
    ;

    val label: String
        get() = when (this) {
            STRICT -> "Streng"
            STANDARD -> "Standard"
            FAST -> "Schnell"
        }
}

/**
 * Reine Entscheidungslogik, ausgewertet an drei Stellen: Sichtbarkeit der beiden
 * Schnell-Einstiege, ob vor dem Feuern ein Ja/Nein-Dialog nötig ist, und ob
 * [de.ble1st.warden.presence.SensitiveActionActivity] für `LOCKDOWN_TASK_ENGAGE` weiterhin den
 * Session-Presence-Kurzweg anbieten darf.
 */
object LockdownTriggerProfilePolicy {

    /** `false` nur für [LockdownTriggerProfile.STRICT] — dort bleibt
     * [de.ble1st.warden.presence.SensitiveActionActivity] der einzige Weg. */
    fun quickTriggerEntryPointsEnabled(profile: LockdownTriggerProfile): Boolean =
        profile != LockdownTriggerProfile.STRICT

    /** Nur [LockdownTriggerProfile.STANDARD] verlangt einen Ja/Nein-Dialog unmittelbar vor dem
     * Feuern; [LockdownTriggerProfile.FAST] feuert sofort, [LockdownTriggerProfile.STRICT] hat
     * ohnehin keinen Schnellauslöser (s. [quickTriggerEntryPointsEnabled]). */
    fun requiresConfirmationDialog(profile: LockdownTriggerProfile): Boolean =
        profile == LockdownTriggerProfile.STANDARD

    /** `false` nur für [LockdownTriggerProfile.STRICT] — dort erzwingt
     * `SensitiveActionActivity` für `LOCKDOWN_TASK_ENGAGE` den vollen Biometrie-/PIN-Pfad, obwohl
     * `SensitiveAction.allowsSessionPresence == true` strukturell weiterhin gilt (die anderen vier
     * session-fähigen Aktionen bleiben davon unberührt, s. Aufrufstelle in
     * `SensitiveActionActivity`). */
    fun allowSessionPresenceReuse(profile: LockdownTriggerProfile): Boolean =
        profile != LockdownTriggerProfile.STRICT
}
