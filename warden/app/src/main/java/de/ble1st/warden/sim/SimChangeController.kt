package de.ble1st.warden.sim

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import de.ble1st.warden.admin.WardenDeviceAdminReceiver
import de.ble1st.warden.domain.presence.DestructiveCommandGuard
import de.ble1st.warden.domain.sim.SimChangeDecision
import de.ble1st.warden.domain.sim.SimChangeOutcome
import de.ble1st.warden.domain.sim.SimChangeReaction
import de.ble1st.warden.wardenAuditLog

/**
 * Android-Glue für [SimChangeDecision] (2026-08-28) — liest den aktuellen Fingerabdruck
 * ([SimFingerprintReader]), vergleicht ihn mit der gespeicherten Baseline und führt die
 * eingestellte [SimChangeReaction] aus. Angestoßen periodisch von [SimChangeWorker] und zusätzlich
 * bei jedem Prozessstart ([de.ble1st.warden.WardenApplication]) — ein Tausch, der während der
 * Nacht passiert, soll nicht erst beim nächsten manuellen App-Start auffallen.
 *
 * **Baseline wird auch bei erkanntem Wechsel sofort fortgeschrieben.** Sonst würde die neue SIM
 * bei jedem weiteren Prüflauf erneut als Wechsel gelten — bei der Reaktion
 * [SimChangeReaction.NEUSTART] wäre das eine Neustartschleife, aus der die Besitzerin das Gerät
 * nicht mehr herausbekommt, ohne die SIM zu tauschen. Gemeldet wird also **einmal pro Wechsel**,
 * nicht pro Prüflauf.
 *
 * **Debug-Build-Hardblock:** [SimChangeReaction.NEUSTART] läuft über denselben
 * [DestructiveCommandGuard] wie jede andere automatische, presence-lose Reaktion im Projekt.
 * [SimChangeReaction.SPERREN] ist davon bewusst ausgenommen — `lockNow()` ist reversibel (einmal
 * entsperren) und im Projekt ohnehin als ungegateter Dashboard-Knopf vorhanden.
 */
class SimChangeController(private val context: Context) {

    private val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)

    fun checkAndMaybeReact(isDebugBuild: Boolean) {
        val reaction = SimChangeStorage.loadReaction(context) ?: return
        val outcome = SimChangeDecision.evaluate(
            storedFingerprint = SimChangeStorage.loadBaseline(context),
            currentFingerprint = SimFingerprintReader(context).fingerprint(),
        )
        val logStore = wardenAuditLog(context)

        when (outcome) {
            SimChangeOutcome.NotReadable, SimChangeOutcome.Unchanged -> return

            is SimChangeOutcome.BaselineEstablished -> {
                SimChangeStorage.saveBaseline(context, outcome.fingerprint)
                logStore.append(Log.INFO, TAG, "SIM-Baseline gesetzt — ab jetzt wird auf Wechsel geprüft")
            }

            is SimChangeOutcome.Changed -> {
                SimChangeStorage.saveBaseline(context, outcome.newFingerprint)
                val what = if (outcome.simRemoved) "SIM entfernt" else "andere SIM eingelegt"
                logStore.append(Log.WARN, TAG, "SIM-Wechsel erkannt ($what) — Reaktion: ${reaction.label}")
                runCatching { SimChangeNotifier(context).notifyChange(outcome.simRemoved, reactionText(reaction)) }
                    .onFailure { Log.w(TAG, "Benachrichtigung fehlgeschlagen", it) }
                react(reaction, isDebugBuild, logStore::append)
            }
        }
    }

    private fun react(
        reaction: SimChangeReaction,
        isDebugBuild: Boolean,
        appendLog: (Int, String, String) -> Unit,
    ) {
        if (reaction == SimChangeReaction.NUR_MELDEN) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null) {
            appendLog(Log.ERROR, TAG, "SIM-Reaktion nicht ausführbar — DevicePolicyManager nicht verfügbar")
            return
        }
        try {
            when (reaction) {
                SimChangeReaction.NUR_MELDEN -> Unit
                SimChangeReaction.SPERREN -> dpm.lockNow()
                SimChangeReaction.NEUSTART ->
                    if (DestructiveCommandGuard.isExecutionAllowed(isDebugBuild)) {
                        dpm.reboot(admin)
                    } else {
                        appendLog(Log.WARN, TAG, "SIM-Neustart unterdrückt — Debug-Build")
                    }
            }
        } catch (e: Exception) {
            appendLog(Log.ERROR, TAG, "SIM-Reaktion (${reaction.label}) fehlgeschlagen: $e")
        }
    }

    private fun reactionText(reaction: SimChangeReaction): String = when (reaction) {
        SimChangeReaction.NUR_MELDEN -> "Es wurde nur protokolliert."
        SimChangeReaction.SPERREN -> "Das Gerät wurde sofort gesperrt."
        SimChangeReaction.NEUSTART -> "Das Gerät wird neu gestartet."
    }

    private companion object {
        const val TAG = "SimChange"
    }
}
