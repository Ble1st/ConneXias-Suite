package de.ble1st.warden.domain.pin

/**
 * Meilenstein H.8 (Konzept Abschnitt 7: "Gate: Ohne bestandenen Notruf-Testfall im schärfsten
 * Zustand verweigert der Lockdown-Eintrag `apply()`"). Strukturell erzwungen statt nur
 * dokumentiert — dieselbe Verteidigungslinien-Idee wie `CapabilityMatrix.NEVER_ON_BUS`/
 * `MasterSwitch`: ein einzelnes Bit (`emergencyCallDrillPassed`) entscheidet, nicht implizites
 * Verhalten. **Muss** durch einen echten, manuell durchgeführten Drill gesetzt werden — nie
 * automatisch/stillschweigend `true`. Der reale Drill bleibt für diese Runde bewusst ungefahren
 * (Risiko: das Testgerät könnte im Lock-Task-Modus hängenbleiben, falls der Notruf-Escape-Pfad
 * nicht wie erwartet funktioniert) — dieses Gate existiert und ist getestet. Seit
 * "LockMode/Threat-Protection-Ausbau" (2026-08-25) hat `WardenLockTaskManager.startLockTask()`
 * reale Aufrufer (s. dessen Klassendoc), die `emergencyCallDrillPassed` aus einem persistierten,
 * aber nie implizit gesetzten Bit lesen — dieses Gate bleibt trotzdem die strukturelle
 * Letztinstanz: ein Aufrufer, der versehentlich `true` hartkodieren würde, würde immer noch am
 * `DestructiveCommandGuard`-Debug-Build-Hardblock der beiden echten Aufrufer scheitern, s. dort.
 *
 * Anders als im ConneXias-Framework-Quellprojekt gibt es hier kein `SentinelWatchdogDecision`
 * mehr (Cross-Process-`linkToDeath()`-Todeseskalation) — dieses Gate lief schon im Quellprojekt
 * unabhängig davon und ist unverändert portierbar (s. Plan-Abschnitt "Presence: Sentinels
 * PIN-Logik portiert").
 */
object WardenLockTaskGate {
    fun isLockTaskPermitted(emergencyCallDrillPassed: Boolean): Boolean = emergencyCallDrillPassed
}
