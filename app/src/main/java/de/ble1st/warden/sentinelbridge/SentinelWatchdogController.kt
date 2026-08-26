package de.ble1st.warden.sentinelbridge

import android.content.Context
import android.util.Log
import de.ble1st.warden.registry.MasterSwitch
import de.ble1st.warden.registry.PersistentSafeguardRegistry
import de.ble1st.warden.registry.RegistryStorage
import de.ble1st.warden.registry.SafeguardCatalog
import de.ble1st.warden.registry.SafeguardRegistryStore
import de.ble1st.warden.registry.WardenLockTaskAuthorizer
import de.ble1st.warden.domain.registry.SafeguardRegistry
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
 * beenden (`adb shell am task lock stop` griff dort nachweislich NICHT). Zusätzlich
 * [MasterSwitch.disarm] (dieselbe "nur revert-Richtung, kein neues Risiko"-Sicherheit wie überall
 * im Projekt).
 */
class SentinelWatchdogController(context: Context) {

    private val appContext = context.applicationContext
    private val authorizer = WardenLockTaskAuthorizer(appContext)
    private val logStore: HashChainLogStore = wardenAuditLog(appContext)
    private val masterSwitch: MasterSwitch by lazy { buildMasterSwitch(appContext) }
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
        logStore.append(Log.ERROR, TAG, "Eskalation: Sentinel-Watchdog entfernt DPM-Whitelist + revertiert Registry")
        authorizer.revert()
        masterSwitch.disarm()
    }

    private fun buildMasterSwitch(context: Context): MasterSwitch {
        val registry = PersistentSafeguardRegistry(
            SafeguardRegistry(),
            SafeguardRegistryStore(RegistryStorage.buildEnvelopeFile(context)),
        )
        // registerAll (nicht nur ein reduziertes Subset wie im Quellprojekt) — Wardens
        // MasterSwitch deckt an jeder anderen Stelle im Projekt (FailsafeActivity,
        // SensitiveActionActivity) ebenfalls den vollen Katalog ab; ein Eskalationspfad, der
        // weniger zurücksetzt als der reguläre "Alle Safeguards zurücksetzen"-Weg, wäre
        // inkonsistent.
        SafeguardCatalog.registerAll(registry, context)
        registry.load()
        return MasterSwitch(registry)
    }

    private companion object {
        const val TAG = "SentinelWatchdog"
    }
}
