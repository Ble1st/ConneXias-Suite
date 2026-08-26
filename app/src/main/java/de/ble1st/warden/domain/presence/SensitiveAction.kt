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
 * **`LOCKDOWN_TASK_ENGAGE` (2026-08-25, "LockMode/Threat-Protection-Ausbau", auf ausdrücklichen
 * Nutzerwunsch: "ausarbeiten von LockMode (WardenLockTask) mit Auslöser, kein Kiosk-Modus"):**
 * der manuelle, presence-gated Weg, ein bereits per [de.ble1st.warden.registry
 * .WardenLockTaskAuthorizer] autorisiertes `startLockTask()` tatsächlich anzustoßen (bisher gab es
 * dafür nirgends einen Aufrufer, s. `de.ble1st.warden.pin.WardenLockTaskManager`-Klassendoc).
 * **Kein Kiosk-Modus:** `WardenLockTaskAuthorizer` setzt weiterhin nur `EMERGENCY_PRESERVING
 * _FEATURES` (Keyguard + Notfall-Anrufe bleiben erreichbar, kein `LOCK_TASK_FEATURE_NONE`), und
 * ein Ausstieg bleibt jederzeit über dieselbe presence-gated Aktion erreichbar (`stopLockTask()`
 * über `de.ble1st.warden.ui.WardenStatusActivity`, gated nur durch die ohnehin für den Bildschirm
 * nötige `WardenLockSession`, kein zweiter Presence-Prompt für den risikofreien Ausstieg selbst —
 * dieselbe "raus ist immer leicht" Haltung, die ein echter Kiosk-Modus gerade nicht hätte).
 * Zusätzlich zum manuellen Weg hier existiert ein automatischer Auslöser
 * (`de.ble1st.warden.domain.pin.WardenLockTaskAutoEngageDecision`) für kritische Bedrohungsfunde —
 * der läuft bewusst *nicht* über diese Aktion/[de.ble1st.warden.presence
 * .DestructiveActionExecutor] (kein frischer Presence-Nachweis in dem Moment verfügbar), sondern
 * über einen eigenen, mehrfach eigenständig gegateten Pfad, s. dessen Klassendoc.
 * `allowsSessionPresence = true` — derselbe bereits beim App-Eintritt erbrachte Nachweis deckt
 * auch diesen manuellen Weg ab.
 */
enum class SensitiveAction(val confirmationPhrase: String, val displayName: String, val allowsSessionPresence: Boolean) {
    WIPE_DATA("WIPE", "Werksreset (nicht ausgeführt)", allowsSessionPresence = false),
    REBOOT("REBOOT", "Gerät neu starten", allowsSessionPresence = true),
    MASTER_SWITCH_REVERT("REVERT", "Alle Safeguards zurücksetzen", allowsSessionPresence = true),
    LOCK_NOW("LOCK", "Sofort sperren", allowsSessionPresence = true),
    LOCKDOWN_MODE_ARM("LOCKDOWN", "Lockdown-Modus scharf schalten", allowsSessionPresence = true),
    LOCKDOWN_TASK_ENGAGE("LOCKTASK", "App-Lock (Lock-Task) jetzt aktivieren", allowsSessionPresence = true),
}
