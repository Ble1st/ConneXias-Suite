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
 *
 * **Zweite Bedingung [pinConfigured] (2026-08-28, aus der Code-/Sicherheitsanalyse):** ohne sie
 * war der Kiosk in seinem wichtigsten Zustand wirkungslos. `SentinelActivity` startete den
 * Lock-Task unabhängig vom PIN-Zustand; war noch keine Sentinel-PIN eingerichtet, zeigte derselbe
 * Bildschirm im laufenden Kiosk die **Ersteinrichtung** — wer das Gerät in der Hand hielt, vergab
 * eine PIN und war sofort wieder draußen. Der Kiosk sperrte damit genau die Person aus, die ihn
 * gar nicht überwinden musste, und niemanden sonst.
 *
 * Warden kann das von außen nicht prüfen: Sentinels PIN-Blob liegt in dessen eigener UID, es gibt
 * keine API dafür, und `SentinelInstallStatusReader` beantwortet nur "installiert ja/nein". Die
 * Prüfung gehört deshalb hierher, an die Stelle, die den Zustand tatsächlich lesen kann.
 *
 * `pinConfigured` ist bewusst `false` für einen **beschädigten** Blob
 * ([SentinelPinStateDecision.LoadResult.Corrupted]) und nicht nur für einen fehlenden: eine PIN,
 * die sich nicht mehr verifizieren lässt, ist als Ausstiegsweg wertlos — der Kiosk wäre dann
 * unverlassbar (Warden ist im Lock-Task nicht erreichbar, es bliebe der Werksreset). Lieber gar
 * nicht erst hineingehen.
 */
object SentinelLockTaskGate {
    fun isLockTaskPermitted(emergencyCallDrillPassed: Boolean, pinConfigured: Boolean): Boolean =
        emergencyCallDrillPassed && pinConfigured
}
