package de.ble1st.warden.sentinelbridge

import android.content.Context
import android.util.Log
import de.ble1st.warden.registry.WardenLockTaskAuthorizer
import de.ble1st.warden.logging.HashChainLogStore
import de.ble1st.warden.wardenAuditLog

/**
 * "Sentinel: eigenständige Kiosk-PIN-App" (2026-08-26), Plan-Abschnitt "Watchdog-Sicherheitsnetz
 * kommt in v1 mit" — Port aus dem ConneXias-Framework-Quellprojekt
 * (`warden-app/.../sentinel/SentinelWatchdogController.kt`), an Wardens aktuelle Struktur
 * angepasst. Bündelt zwei Bausteine zu einem einzigen Schalter:
 *
 * - [WardenLockTaskAuthorizer] — DPM-Whitelist für Sentinels Paket.
 * - [SentinelDeathWatchdog] — Cross-Process-Death-Erkennung, solange Sentinel real scharf
 *   geschaltet ist.
 *
 * **Warum ein eigener Controller statt beides direkt in [SentinelLockdownEngager]:**
 * [SentinelDeathWatchdog] hält eine echte, lebende `bindService()`-Verbindung + In-Memory-
 * Todeszeitstempel — an einen zustandslosen `object`-Aufruf wie `SentinelLockdownEngager.engage()`
 * gebunden, wäre sie bei jedem Aufruf neu und nie wieder erreichbar, um sie zu entschärfen. Ein
 * einziges, app-weit langlebiges Exemplar (gehalten von
 * [de.ble1st.warden.WardenApplication], `by lazy` — dieselbe "nie eager in `onCreate()`"-
 * Vorsicht wie bei jeder anderen App-weiten Instanz dort) ist die korrekte Lebensdauer.
 *
 * **Eskalation (Plan-Diagramm "3 Deaths/60s → escalate()"):** [escalate] entfernt Sentinel real
 * aus der DPM-Lock-Task-Whitelist — laut dem im ConneXias-Framework-Quellprojekt empirisch
 * bestätigten Notruf-Drill-Fund der tatsächlich wirksame Weg, einen aktiven Lock-Task-Zustand zu
 * beenden (`adb shell am task lock stop` griff dort nachweislich NICHT). Genau das und nichts
 * weiter.
 *
 * **Kein `MasterSwitch.disarm()` mehr (Korrektur 2026-08-28, aus der Code-/Sicherheitsanalyse):**
 * bis dahin revertierte [escalate] zusätzlich den *gesamten* Katalog über
 * `SafeguardCatalog.registerAll` — inklusive `DeviceLockdownBundle`, Werksreset-Schutz,
 * Kamerasperre und `SentinelUninstallProtectionSafeguard`. Die Begründung dafür ("nur
 * revert-Richtung, kein neues Risiko") übersieht, *wer* diesen Pfad auslöst: dreimaliges Sterben
 * von Sentinels Prozess innerhalb von 60 Sekunden ist genau das, was jemand mit dem Gerät in der
 * Hand provozieren würde, um aus dem Kiosk herauszukommen. Damit war der Watchdog ein ungegateter
 * Weg zu exakt dem Effekt, den
 * [de.ble1st.warden.domain.presence.SensitiveAction.MASTER_SWITCH_REVERT] hinter
 * Bestätigungsphrase, Presence-Nachweis, Rate-Limit und
 * [de.ble1st.warden.domain.presence.DestructiveCommandGuard] absichert — ein Widerspruch zur
 * Projektregel "structural enforcement over documentation".
 *
 * Ein Wächter darf im Zweifel *mehr* Härtung stehen lassen, nicht weniger. Das Zurückziehen der
 * Whitelist genügt für den eigentlichen Zweck (kein Neustart-Loop eines abstürzenden
 * Kiosk-Prozesses); alles darüber hinaus bleibt der Betreiberin über den presence-gegateten Weg
 * vorbehalten und wird hier nur laut protokolliert.
 */
class SentinelWatchdogController(context: Context) {

    private val appContext = context.applicationContext
    private val authorizer = WardenLockTaskAuthorizer(appContext)
    private val logStore: HashChainLogStore = wardenAuditLog(appContext)
    private val watchdog = SentinelDeathWatchdog(appContext, logStore, onEscalate = { escalate() })

    /** Spiegelt die reale DPM-Whitelist — dieselbe "Wahrheit im System, keine eigene
     * Speicherung"-Haltung wie bei jedem [de.ble1st.warden.domain.registry.Safeguard]. */
    fun isArmed(): Boolean = authorizer.isActive()

    fun arm() {
        authorizer.apply()
        watchdog.start()
        logStore.append(Log.WARN, TAG, "Sentinel-Wächter scharf geschaltet (Lock-Task-Whitelist + Death-Watchdog)")
    }

    fun disarm() {
        watchdog.stop()
        authorizer.revert()
        logStore.append(Log.WARN, TAG, "Sentinel-Wächter entschärft")
    }

    /** `internal` statt `private` — ein künftiger Instrumented-Test kann die Eskalation direkt
     * auslösen, ohne den vollen `linkToDeath`-Ablauf (dreimaliges echtes Sterben von Sentinels
     * Prozess in 60s) inszenieren zu müssen; [SentinelWatchdogDecision.shouldEscalate] selbst ist
     * bereits vollständig JVM-getestet. */
    internal fun escalate() {
        logStore.append(
            Log.ERROR,
            TAG,
            "Eskalation: 3 Sentinel-Prozesstode in 60s — Lock-Task-Whitelist wird zurückgezogen. " +
                "Übrige Safeguards bleiben bewusst scharf (s. Klassendoc); zum Zurücksetzen den " +
                "presence-gegateten Weg 'Alle Safeguards zurücksetzen' nutzen.",
        )
        val outcome = runCatching { authorizer.revert() }
        logStore.append(
            if (outcome.isSuccess) Log.WARN else Log.ERROR,
            TAG,
            if (outcome.isSuccess) {
                "Lock-Task-Whitelist zurückgezogen"
            } else {
                "Lock-Task-Whitelist konnte NICHT zurückgezogen werden: ${outcome.exceptionOrNull()}"
            },
        )
    }

    private companion object {
        const val TAG = "SentinelWatchdog"
    }
}
