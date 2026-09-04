package de.ble1st.warden.domain.bus

/**
 * Kommandoklassen der Concord-Autorisierung — reduzierte Fassung des Quellprojekts (dort zusätzlich
 * `LOCKDOWN_ARM`/`LOCKDOWN_DISARM`/`FAILSAFE_OPS`/`SENTINEL_STATE_SYNC`, alle entweder ohnehin nie
 * über den Bus erlaubt oder nur für den jetzt entfallenen Cross-APK-Sentinel-Mirror relevant).
 */
enum class BusCommand {
    /** Zustand/Status lesen — keine Nebenwirkung. */
    READ,

    /** Ein nicht-destruktiver Schalter (z. B. ein einzelner Safeguard-Registry-Eintrag, App
     * einfrieren/entfrieren). */
    NON_DESTRUCTIVE_SWITCH,

    /** Log-Einsicht anstoßen — löst selbst keine Log-Übertragung aus, sondern nur, ob
     * [de.ble1st.warden.presence.LogViewerActivity] startet; der eigentliche Presence-Nachweis
     * läuft danach in [de.ble1st.warden.presence.LogViewerActivity] selbst. */
    LOG_ACCESS,

    /** Meilenstein "Barbican als eigener Prozess" (2026-08-31, `docs/design-barbican-prozess-
     * childvpn.md`): ein anderer Prozess derselben App meldet ein Ereignis zur Protokollierung —
     * kein Zustand wird gelesen oder verändert, nur ein Log-Eintrag entsteht. Eigene Kategorie
     * statt Missbrauch von [NON_DESTRUCTIVE_SWITCH] (dieselbe "eigene Semantik, eigene Kategorie"-
     * Logik wie bei [LOG_ACCESS]). Ausschließlich [Role.BARBICAN] zugeteilt. */
    EVENT_REPORT,

    /** `wipeData()`/`reboot()`/Masterschalter-Revert — strukturell KEINEM Concord-Aufrufer je
     * erlaubt (s. [CapabilityMatrix.NEVER_ON_BUS]). Kein `ConcordBus`-Methodenäquivalent
     * existiert für dieses Kommando; dieser Enum-Wert dokumentiert die Invariante explizit statt
     * sie nur implizit durch Abwesenheit einer Methode gelten zu lassen — dieselbe
     * "strukturell statt nur dokumentiert erzwungen"-Idee wie im Quellprojekt. Destruktive
     * Aktionen laufen ausschließlich über Wardens eigenen, presence-geschützten Pfad
     * ([de.ble1st.warden.presence.SensitiveActionActivity]/[de.ble1st.warden.presence.DestructiveActionExecutor]). */
    DESTRUCTIVE,
}
