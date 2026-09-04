package de.ble1st.warden.domain.sentinelbridge

/**
 * "Sentinel: eigenständige Kiosk-PIN-App" (2026-08-26), Plan-Abschnitt "Watchdog-Sicherheitsnetz
 * kommt in v1 mit" — reine Eskalations-Entscheidung, Port aus dem ConneXias-Framework-Quellprojekt
 * (`core/domain/.../sentinel/SentinelWatchdogDecision.kt`, dort bereits vollständig JVM-getestet).
 * Die eigentliche `IBinder.linkToDeath()`-Bindung und Zeitstempel-Sammlung lebt in
 * [de.ble1st.warden.sentinelbridge.SentinelDeathWatchdog] (Android-`Binder`-API nötig, deshalb
 * außerhalb des `domain`-Pakets, s. CLAUDE.md "Decision/Executor-Trennung").
 */
object SentinelWatchdogDecision {

    private const val ESCALATION_THRESHOLD = 3
    private const val ESCALATION_WINDOW_MILLIS = 60_000L

    /** `deathTimestampsEpochMillis` in beliebiger Reihenfolge — wird intern auf das
     * [ESCALATION_WINDOW_MILLIS]-Fenster vor `nowEpochMillis` gefiltert. `nowEpochMillis` als
     * Parameter statt `System.currentTimeMillis()` intern — testbar ohne Uhr-Mocking. */
    fun shouldEscalate(deathTimestampsEpochMillis: List<Long>, nowEpochMillis: Long): Boolean {
        val recentDeaths = deathTimestampsEpochMillis.count { nowEpochMillis - it in 0..ESCALATION_WINDOW_MILLIS }
        return recentDeaths >= ESCALATION_THRESHOLD
    }
}
