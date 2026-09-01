package de.ble1st.warden.appmanagement

import android.content.Context
import androidx.core.content.edit
import de.ble1st.warden.domain.appmanagement.SuspiciousAppFinding
import de.ble1st.warden.domain.appmanagement.ThreatSeverity

/**
 * Der höchste Schweregrad des zuletzt **behandelten** Scan-Laufs (Befund Q-9, 2026-08-29).
 *
 * **Wozu:** [de.ble1st.warden.profile.AutoProfileController] braucht für seine
 * Bedrohungs-Eskalation nur eine einzige Information — "steht gerade ein `CRITICAL`-Fund?". Es
 * holte sie sich bisher über `ConcordBus.listSuspiciousAppFindings()`, was einen **vollständigen
 * zweiten Paket-Scan** über alle installierten Apps auslöste, zusätzlich zu dem, den
 * [SuspiciousAppScanWorker] im selben 15-Minuten-Takt ohnehin fährt. Zwei komplette
 * `QUERY_ALL_PACKAGES`-Durchläufe pro Viertelstunde sind in einer App, die selbst einen
 * Akku-Drain-Monitor mitbringt, schwer zu rechtfertigen.
 *
 * **Wer schreibt:** ausschließlich die beiden Wege, die einen Fund auch *behandeln*
 * ([SuspiciousAppScanController.scanAndEnforce] und
 * [SuspiciousAppScanController.runImmediateScan]) — dieselbe Regel wie beim Baseline-Commit
 * (s. "Wer die Baselines vorrücken darf" im [SuspiciousAppScanController]-Klassendoc). Ein reiner
 * Lesepfad (Dashboard-Liste) schreibt hier nichts, sonst hinge der Bedrohungsstand davon ab, ob
 * jemand zufällig einen Bildschirm geöffnet hat.
 *
 * **Preis dieser Lösung, bewusst akzeptiert:** der gelesene Wert ist bis zu einen Worker-Takt alt
 * (max. ~15 Minuten). Da `AutoProfileController` selbst nur alle 15 Minuten läuft, verschiebt sich
 * eine Eskalation dadurch im ungünstigsten Fall um einen Takt — gegenüber einem
 * Dauer-Doppelscan die deutlich bessere Seite des Tauschs. Für den eiligen Fall gibt es ohnehin den
 * unabhängigen, sofort reagierenden Pfad
 * ([SuspiciousAppScanController.notifyNewFindings] → Lock-Task-Auto-Engage), der nicht über das
 * Auto-Profil läuft.
 *
 * **`null` heißt "noch nie ein behandelter Lauf"**, nicht "keine Bedrohung" — der Aufrufer
 * entscheidet, was daraus folgt (`AutoProfileController` behandelt es wie "unbekannt" und
 * eskaliert nicht, dieselbe Fail-Safe-Haltung wie beim bisherigen Lesefehler-Zweig dort).
 *
 * Normale (credential-verschlüsselte) `SharedPreferences` wie bei
 * [de.ble1st.warden.profile.AutoProfileStorage] — der einzige Leser läuft ohnehin nur in einem
 * periodischen WorkManager-Lauf nach der ersten Entsperrung.
 */
object SuspiciousAppThreatLevelStore {
    private const val PREFS_NAME = "warden_suspicious_threat_level"
    private const val KEY_HIGHEST_SEVERITY = "highest_severity"

    /** Aus der kompletten Fundliste eines behandelten Laufs den höchsten Schweregrad ableiten und
     * festhalten. Eine leere Fundliste ist ein *echtes* Ergebnis ("nichts gefunden") und wird als
     * [ThreatSeverity.INFO] gespeichert — nicht als `null`, das für "nie gelaufen" reserviert
     * bleibt. */
    fun record(context: Context, findings: List<SuspiciousAppFinding>) {
        val highest = findings
            .map { ThreatSeverity.highest(it.signals) }
            .maxByOrNull { it.ordinal }
            ?: ThreatSeverity.INFO
        prefs(context).edit { putString(KEY_HIGHEST_SEVERITY, highest.name) }
    }

    /** `null` = noch nie ein behandelter Scan-Lauf, s. Klassendoc. */
    fun highestSeverity(context: Context): ThreatSeverity? =
        prefs(context).getString(KEY_HIGHEST_SEVERITY, null)
            ?.let { name -> ThreatSeverity.entries.firstOrNull { it.name == name } }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
