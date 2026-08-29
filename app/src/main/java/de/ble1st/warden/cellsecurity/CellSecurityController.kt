package de.ble1st.warden.cellsecurity

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import de.ble1st.warden.admin.WardenDeviceAdminReceiver
import de.ble1st.warden.domain.appmanagement.ThreatSeverity
import de.ble1st.warden.domain.cellsecurity.CellSecurityDecision
import de.ble1st.warden.domain.cellsecurity.CellSecurityOutcome
import de.ble1st.warden.domain.cellsecurity.CellSecurityReaction
import de.ble1st.warden.domain.presence.DestructiveCommandGuard
import de.ble1st.warden.netlock.NetLockdownController
import de.ble1st.warden.wardenAuditLog

/**
 * Android-Glue für [CellSecurityDecision] (2026-08-29) — liest den aktuellen Messwert
 * ([CellObservationReader]), vergleicht ihn mit dem letzten gespeicherten und führt die
 * eingestellte [CellSecurityReaction] aus. Angestoßen periodisch von [CellSecurityWorker] und
 * zusätzlich bei jedem Prozessstart ([CellSecurityStartupWorker]) — dasselbe Muster wie
 * [de.ble1st.warden.sim.SimChangeController].
 *
 * **Reaktion nur bei [ThreatSeverity.CRITICAL]**, nie bei bloßem `WARNING` — s. Begründung an
 * [de.ble1st.warden.domain.cellsecurity.CellSecurityReaction]s Klassendoc: die Einzelindikatoren,
 * die für sich genommen nur `WARNING` auslösen, sind bewusst zu unsicher für eine automatische
 * Konsequenz. `WARNING`-Funde werden trotzdem protokolliert und benachrichtigt, nur ohne
 * [react].
 *
 * **Letzter Messwert wird auch bei erkannter Auffälligkeit sofort fortgeschrieben** — sonst würde
 * dieselbe (dann schon bekannte) Zelle bei jedem weiteren Prüflauf erneut als Auffälligkeit gelten,
 * bei [CellSecurityReaction.NEUSTART] eine Neustartschleife. Gemeldet wird also einmal pro
 * beobachtetem Übergang, nicht pro Prüflauf — exakt [de.ble1st.warden.sim.SimChangeController]s
 * Begründung.
 */
class CellSecurityController(private val context: Context) {

    private val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)

    fun checkAndMaybeReact(isDebugBuild: Boolean) {
        val reaction = CellSecurityStorage.loadReaction(context) ?: return
        val outcome = CellSecurityDecision.evaluate(
            previous = CellSecurityStorage.loadObservation(context),
            current = CellObservationReader(context).observe(),
        )
        val logStore = wardenAuditLog(context)

        when (outcome) {
            CellSecurityOutcome.NotReadable -> return

            is CellSecurityOutcome.BaselineEstablished -> {
                CellSecurityStorage.saveObservation(context, outcome.observation)
                logStore.append(Log.INFO, TAG, "Mobilfunkzellen-Baseline gesetzt — ab jetzt wird auf Auffälligkeiten geprüft")
            }

            is CellSecurityOutcome.Unchanged -> {
                CellSecurityStorage.saveObservation(context, outcome.observation)
            }

            is CellSecurityOutcome.Suspicious -> {
                CellSecurityStorage.saveObservation(context, outcome.observation)
                val indicatorText = outcome.indicators.joinToString(", ") { it.label }
                val appliesReaction = outcome.severity == ThreatSeverity.CRITICAL
                val reactionNote = if (appliesReaction) reaction.label else "nur protokolliert (${outcome.severity})"
                logStore.append(
                    if (outcome.severity == ThreatSeverity.CRITICAL) Log.ERROR else Log.WARN,
                    TAG,
                    "Mobilfunkzellen-Auffälligkeit erkannt ($indicatorText) — $reactionNote",
                )
                runCatching { CellSecurityNotifier(context).notify(outcome, reactionText(reaction, appliesReaction)) }
                    .onFailure { Log.w(TAG, "Benachrichtigung fehlgeschlagen", it) }
                if (appliesReaction) {
                    react(reaction, isDebugBuild, logStore::append)
                }
            }
        }
    }

    private fun react(
        reaction: CellSecurityReaction,
        isDebugBuild: Boolean,
        appendLog: (Int, String, String) -> Unit,
    ) {
        if (reaction == CellSecurityReaction.NUR_MELDEN) return
        try {
            when (reaction) {
                CellSecurityReaction.NUR_MELDEN -> Unit
                CellSecurityReaction.NETZWERK_SPERREN -> {
                    val result = NetLockdownController(context).arm()
                    if (result !is NetLockdownController.ArmResult.Success) {
                        appendLog(Log.ERROR, TAG, "Netz-Sperre-Reaktion fehlgeschlagen: $result")
                    }
                }
                CellSecurityReaction.NEUSTART -> {
                    val dpm = context.getSystemService(DevicePolicyManager::class.java)
                    if (dpm == null) {
                        appendLog(Log.ERROR, TAG, "Mobilfunkzellen-Reaktion nicht ausführbar — DevicePolicyManager nicht verfügbar")
                        return
                    }
                    if (DestructiveCommandGuard.isExecutionAllowed(isDebugBuild)) {
                        dpm.reboot(admin)
                    } else {
                        appendLog(Log.WARN, TAG, "Mobilfunkzellen-Neustart unterdrückt — Debug-Build")
                    }
                }
            }
        } catch (e: Exception) {
            appendLog(Log.ERROR, TAG, "Mobilfunkzellen-Reaktion (${reaction.label}) fehlgeschlagen: $e")
        }
    }

    private fun reactionText(reaction: CellSecurityReaction, applies: Boolean): String = when {
        !applies -> "Es wurde nur protokolliert."
        reaction == CellSecurityReaction.NUR_MELDEN -> "Es wurde nur protokolliert."
        reaction == CellSecurityReaction.NETZWERK_SPERREN -> "Die Netz-Sperre wurde aktiviert."
        reaction == CellSecurityReaction.NEUSTART -> "Das Gerät wird neu gestartet."
        else -> ""
    }

    private companion object {
        const val TAG = "CellSecurity"
    }
}
