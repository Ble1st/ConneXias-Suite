package de.ble1st.warden.domain.antitheft

/**
 * Reine Auslöse-Logik für [de.ble1st.warden.antitheft.AntiTheftAlarmController] (2026-09-03) —
 * getrennt von der Sensor-/Broadcast-Verarbeitung, dieselbe Decision/Executor-Trennung wie überall
 * sonst im Projekt.
 *
 * **Beide Auslöser wirken nur bei gesperrtem Gerät** — ein Diebstahlschutz-Alarm beim normalen,
 * beabsichtigten Gebrauch (Handy in die Tasche stecken, Ladekabel abziehen während man es selbst
 * benutzt) wäre kein Sicherheitsgewinn, nur eine Fehlalarmquelle, die den Nutzer dazu verleiten
 * würde, das Feature wieder abzuschalten.
 *
 * `magnitude`/`thresholdMs2` stammen von `Sensor.TYPE_LINEAR_ACCELERATION` (Schwerkraft bereits vom
 * Android-Sensor-Framework herausgerechnet, s. [de.ble1st.warden.antitheft.AntiTheftMotionMonitor]
 * -Klassendoc) — ein einzelner Schwellenwertvergleich, keine eigene Baseline nötig. **Heuristik,
 * kein verifizierter Erkennungsmechanismus** (dieselbe Ehrlichkeit wie
 * [de.ble1st.warden.domain.cellsecurity.CellSecurityDecision]): der Schwellenwert ist nicht gegen
 * reale Diebstahlszenarien kalibriert, nur gegen die Grundüberlegung "spürbar mehr Bewegung als ein
 * ruhig liegendes Gerät".
 */
object AntiTheftAlarmDecision {

    fun shouldTriggerOnMotion(
        config: AntiTheftConfig,
        isLocked: Boolean,
        magnitude: Float,
        thresholdMs2: Float,
    ): Boolean = config.motionAlarmEnabled && isLocked && magnitude > thresholdMs2

    fun shouldTriggerOnChargerDisconnect(config: AntiTheftConfig, isLocked: Boolean): Boolean =
        config.chargerAlarmEnabled && isLocked
}
