package de.ble1st.warden.registry

/**
 * Meilenstein C.6 (Konzept Abschnitt 4/19): "Masterschalter (revertet gesamte Registry) —
 * vorerst ohne Presence." Revertiert **jeden** registrierten Safeguard, unabhängig von dessen
 * aktuellem Soll- oder Ist-Zustand — die "alles aus"-Notbremse.
 *
 * **Scope-Hinweis:** anders als im ConneXias-Framework-Quellprojekt (dort zusätzlich optionale
 * `disarmSentinel`/`disarmNetLockdown`-Lambdas für den Cross-APK-Sentinel-Watchdog und
 * Barbican/VPN) deckt dieser `MasterSwitch` nur noch die Registry selbst ab: Sentinels
 * PIN-/Lock-Task-Logik läuft jetzt in Wardens eigenem Prozess (kein separater
 * `SentinelWatchdogController` mehr, den es zu disarmen gäbe), VPN/Barbican entfällt vollständig
 * (s. Plan-Context-Abschnitt).
 *
 * **Bewusst (noch) ohne Presence-Schutz** — Milestone-Text: "vorerst ohne Presence". Diese
 * Klasse selbst prüft keine Autorisierung; ein künftiger Aufrufer (Wardens presence-geschützter
 * Eigen-Pfad — "Lockdown-Disarm" ist in Invariante 1 explizit als presence-pflichtig genannt) ist
 * dafür verantwortlich. **Deshalb bewusst nirgends im aktuellen Code an einen erreichbaren
 * Trigger (UI, Bus, Broadcast) angeschlossen** — anders als eine bloße Fehlfunktion wäre ein
 * ungated Trigger ein echter Sicherheitsrückschritt, kein reines Funktionslücken-Risiko.
 *
 * **Immer unbedingt, nie bedingt durch `isActive()`:** anders als [RegistryReconciler] (der nur
 * tatsächlich divergente Einträge anfasst) ruft [disarm] `revert()` für **jede** registrierte id
 * auf, ohne vorher den Ist-Zustand zu prüfen — eine Notbremse darf sich nicht auf einen
 * möglicherweise selbst kaputten Status-Check verlassen, um zu entscheiden, ob sie etwas tut.
 *
 * **Best-Effort pro Eintrag:** wie [RegistryReconciler] hält ein fehlschlagender Safeguard die
 * übrigen nicht auf — jeder Eintrag wird einzeln versucht, ein Fehler bei einem Eintrag bricht
 * die Schleife nicht ab.
 */
class MasterSwitch(
    private val registry: PersistentSafeguardRegistry,
    private val onResult: (MasterSwitchResult) -> Unit = {},
) {

    /**
     * Revertiert alle registrierten Einträge. Liefert für **jede** id ein Ergebnis, auch für
     * bereits inaktive — anders als [RegistryReconciler.reconcile], das Divergenzfreies
     * überspringt: der Masterschalter soll sichtbar über jeden einzelnen Eintrag gelaufen sein,
     * nicht stillschweigend nichts für ihn liefern.
     */
    fun disarm(): List<MasterSwitchResult> {
        val results = mutableListOf<MasterSwitchResult>()
        for (id in registry.registeredIds()) {
            val result = disarmOne(id)
            results += result
            onResult(result)
        }
        return results
    }

    private fun disarmOne(id: String): MasterSwitchResult = try {
        registry.revert(id)
        MasterSwitchResult.Disarmed(id)
    } catch (e: Exception) {
        MasterSwitchResult.Failed(id, e)
    }
}

/** Ergebnis eines einzelnen [MasterSwitch.disarm]-Schritts für eine `id`. */
sealed class MasterSwitchResult {
    abstract val id: String

    /** `revert()` erfolgreich durchgeführt (auch wenn der Eintrag vorher schon inaktiv war). */
    data class Disarmed(override val id: String) : MasterSwitchResult()

    /** `revert()` fehlgeschlagen — betrifft nur diese eine id, andere Einträge werden trotzdem
     * weiter bearbeitet. */
    data class Failed(override val id: String, val cause: Throwable) : MasterSwitchResult()
}
