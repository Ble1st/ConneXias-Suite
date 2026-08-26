package de.ble1st.warden.sentinel.domain

/**
 * Strukturell erzwungenes Gate für den einzigen Zweck, für den Sentinel überhaupt existiert:
 * `Activity.startLockTask()` real auszulösen. 1:1 dieselbe Idee wie das alte
 * `de.ble1st.warden.domain.pin.WardenLockTaskGate` (jetzt in Warden entfernt, s. Plan-Abschnitt
 * "Änderungen in :app") — [emergencyCallDrillPassed] wird **nie** lokal in Sentinel gespeichert
 * oder erraten, sondern kommt bei jedem Scharfschalten frisch von Warden mit
 * ([de.ble1st.warden.sentinel.SentinelActivity]s `EXTRA_EMERGENCY_CALL_DRILL_PASSED`-Extra,
 * gespiegelt aus Wardens eigenem `WardenLockTaskDrillStorage`-Bit). Ein Aufrufer, der
 * versehentlich `true` hartkodieren würde, würde in der Praxis trotzdem am
 * `DestructiveCommandGuard`-Debug-Build-Hardblock auf Wardens Seite scheitern, bevor Sentinel
 * überhaupt gestartet wird — dieselbe Verteidigungslinien-Idee wie überall im Projekt.
 */
object SentinelLockTaskGate {
    fun isLockTaskPermitted(emergencyCallDrillPassed: Boolean): Boolean = emergencyCallDrillPassed
}
