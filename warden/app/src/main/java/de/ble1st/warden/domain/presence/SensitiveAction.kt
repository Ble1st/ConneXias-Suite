package de.ble1st.warden.domain.presence

/**
 * Die Kommandos, die "hinter Presence + mehrstufiger Bestätigung" laufen — Wardens eigener,
 * presence-geschützter Pfad (Invariante 1), nie über Concord (dort strukturell unerreichbar, s.
 * `domain.bus.CapabilityMatrix`/`BusCommand`). Bewusst ein eigenes, kleineres Enum statt
 * Wiederverwendung von `BusCommand` — dort geht es um Kommando*klassen* für die
 * Concord-Autorisierung, hier um die konkreten, real existierenden destruktiven/rückbau-scharfen
 * Aktionen dieses Projekts.
 *
 * `confirmationPhrase` ist der Bestätigungstext der mehrstufigen Bestätigung: der Nutzer muss ihn
 * exakt eintippen, *bevor* die Presence-Prüfung (Biometrie oder lokaler PIN) überhaupt angestoßen
 * wird.
 *
 * **Kein `SENTINEL_RESET` mehr** (im Quellprojekt: Sentinels PIN über einen Cross-APK-Bus-Aufruf
 * zurücksetzen) — Sentinels PIN-Logik läuft jetzt lokal in Warden
 * ([de.ble1st.warden.presence.WardenPinActivity]); ein `Corrupted`-Zustand des lokalen
 * PIN-Blobs wird stattdessen über den ohnehin vorhandenen Offline-Failsafe
 * ([de.ble1st.warden.failsafe.FailsafeActivity]) behandelt statt über einen eigenen, jetzt
 * redundanten Recovery-Mechanismus.
 *
 * **`LOCK_NOW` (Tier 2, "Anti-Diebstahl/Fernsperre", 2026-08-22) — bewusst weiterhin lokal, kein
 * Fernzugriff:** trotz des Namens kein Remote-Kommando — es gibt in diesem Projekt keinen
 * Server-/Push-Kanal, der es auslösen könnte. Der Nutzer tippt es selbst auf dem entsperrten Gerät
 * an (z. B. bevor er es kurzzeitig weitergibt), derselbe presence-gated Pfad wie `REBOOT`. Trotzdem
 * hier statt als reiner [de.ble1st.warden.bus.BusCommand.NON_DESTRUCTIVE_SWITCH] über
 * [de.ble1st.warden.bus.ConcordBus]: `DevicePolicyManager.lockNow()` sperrt sofort und erzwingt
 * die volle Anmeldung erneut — dieselbe "im Zweifel den stärkeren Schutz"-Haltung wie bei den
 * bereits presence-gated Aktionen, kein reiner Ein/Aus-Schalter wie ein Safeguard.
 *
 * **`LOCKDOWN_MODE_ARM` (2026-08-22, "arbeite langsam am Lockdownmodus" — erster Schritt, auf
 * Nutzerwunsch presence-gated statt als einfacher Safeguard-Schalter):** scharft
 * [de.ble1st.warden.registry.DeviceLockdownBundle] (Meilenstein C.5 — USB-Signaling aus,
 * `DISALLOW_SAFE_BOOT`/`DISALLOW_FACTORY_RESET`/`DISALLOW_DEBUGGING_FEATURES`,
 * Lock-Task-Autorisierung) scharf. Bewusst kein eigenes `LOCKDOWN_MODE_REVERT`: das Bündel wird
 * stattdessen in [de.ble1st.warden.presence.SensitiveActionActivity]s eigene Registry
 * aufgenommen, sodass das bereits vorhandene `MASTER_SWITCH_REVERT` es mit zurücksetzt — ein
 * zweiter, paralleler presence-gated Revert-Pfad für dasselbe Bündel wäre nur ein zusätzlicher,
 * unnötig redundanter Angriffs-/Fehlerfläche.
 *
 * **`allowsSessionPresence` (WardenLock, Finalisierungsphase 2026-08-24, auf Nutzerwunsch):** ein
 * frischer Presence-Nachweis beim App-Eintritt ([de.ble1st.warden.presence.WardenLockActivity])
 * darf für diese vier Aktionen als bereits erbracht gelten — kein zweiter, separater Prompt hier.
 * Strukturell (nicht nur dokumentiert) `false` für `WIPE_DATA`: die einzige Aktion ohne jeden
 * Rückweg bleibt an einen *frischen* Nachweis gebunden, egal wie alt der App-Eintritts-Nachweis
 * ist — s. [de.ble1st.warden.presence.DestructiveActionExecutor.executeWithSessionPresence].
 *
 * **`LOCKDOWN_TASK_ENGAGE` (2026-08-25, "LockMode/Threat-Protection-Ausbau", ursprünglich auf
 * ausdrücklichen Nutzerwunsch "kein Kiosk-Modus" — seit "Sentinel: eigenständige Kiosk-PIN-App"
 * jetzt doch ein echter Kiosk-Modus, s. u.):** der manuelle, presence-gated Weg, [de.ble1st.warden
 * .sentinelbridge.SentinelLockdownEngager] anzustoßen — autorisiert per [de.ble1st.warden.registry
 * .WardenLockTaskAuthorizer] die separate Sentinel-App für Lock-Task und startet deren
 * `SentinelActivity`, die dort `startLockTask()` in ihrem eigenen, fremden Prozess aufruft (nicht
 * in Wardens eigenem — s. `SentinelLockdownEngager`-Klassendoc für die volle Begründung des
 * Wechsels). `WardenLockTaskAuthorizer` setzt weiterhin nur `EMERGENCY_PRESERVING_FEATURES`
 * (Keyguard + Notfall-Anrufe bleiben erreichbar), aber sonst *ist* es jetzt Kiosk: einziger
 * Ausstieg ist Sentinels eigene, separate PIN direkt auf dem Gerät — **kein** Warden-Dashboard-Weg
 * mehr (Warden selbst ist ja nicht mehr die eingesperrte App und kann `stopLockTask()` schon
 * technisch nicht mehr für Sentinels Prozess aufrufen).
 * Zusätzlich zum manuellen Weg hier existiert ein automatischer Auslöser
 * (`de.ble1st.warden.domain.pin.WardenLockTaskAutoEngageDecision`) für kritische Bedrohungsfunde —
 * der läuft bewusst *nicht* über diese Aktion/[de.ble1st.warden.presence
 * .DestructiveActionExecutor] (kein frischer Presence-Nachweis in dem Moment verfügbar), sondern
 * über einen eigenen, mehrfach eigenständig gegateten Pfad, s. dessen Klassendoc.
 * `allowsSessionPresence = true` — derselbe bereits beim App-Eintritt erbrachte Nachweis deckt
 * auch diesen manuellen Weg ab.
 *
 * **`TRANSFER_OWNERSHIP` (Tier 3 der DPC-Recherche, 2026-09-05, Nutzerwunsch "changeowner in
 * Erweitert"):** übergibt die Device-Owner-Rolle an eine andere App
 * (`DevicePolicyManager.transferOwnership`). Von allen Aktionen hier die einzige, nach der Warden
 * **selbst keine Rechte mehr hat**, um irgendetwas davon rückgängig zu machen — der Rückweg
 * existiert nur noch in der Zielapp, und wenn die ihn nicht anbietet, hilft nur ein Werksreset.
 * Deshalb `allowsSessionPresence = false`, dieselbe strukturelle Ausnahme wie bei `WIPE_DATA`: der
 * beim App-Eintritt erbrachte Nachweis kann Stunden alt sein, für eine Aktion ohne Rückweg ist das
 * zu wenig. Ein *frischer* Biometrie-/PIN-Nachweis bleibt Pflicht.
 *
 * Das Ziel der Übertragung steckt bewusst **nicht** in diesem Enum: es ist ein zur Laufzeit
 * gewählter `ComponentName`, und die Aktionen hier tragen keine Parameter. Es wird stattdessen
 * beim Öffnen von [de.ble1st.warden.presence.SensitiveActionActivity] als Intent-Extra übergeben
 * und dort in das ausführende Lambda eingeschlossen — s. deren Klassendoc.
 */
enum class SensitiveAction(val confirmationPhrase: String, val displayName: String, val allowsSessionPresence: Boolean) {
    WIPE_DATA("WIPE", "Werksreset (nicht ausgeführt)", allowsSessionPresence = false),
    REBOOT("REBOOT", "Gerät neu starten", allowsSessionPresence = true),
    MASTER_SWITCH_REVERT("REVERT", "Alle Safeguards zurücksetzen", allowsSessionPresence = true),
    LOCK_NOW("LOCK", "Sofort sperren", allowsSessionPresence = true),
    LOCKDOWN_MODE_ARM("LOCKDOWN", "Lockdown-Modus scharf schalten", allowsSessionPresence = true),
    LOCKDOWN_TASK_ENGAGE("LOCKTASK", "App-Lock (Lock-Task) jetzt aktivieren", allowsSessionPresence = true),
    TRANSFER_OWNERSHIP("TRANSFER", "Device-Owner an andere App übertragen", allowsSessionPresence = false),
}
