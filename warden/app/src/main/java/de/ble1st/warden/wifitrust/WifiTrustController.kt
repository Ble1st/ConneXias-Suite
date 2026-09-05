package de.ble1st.warden.wifitrust

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import de.ble1st.warden.admin.WardenDeviceAdminReceiver
import de.ble1st.warden.domain.presence.DestructiveCommandGuard
import de.ble1st.warden.domain.wifitrust.WifiTrustDecision
import de.ble1st.warden.domain.wifitrust.WifiTrustReaction
import de.ble1st.warden.netlock.NetLockdownController
import de.ble1st.warden.wardenAuditLog

/**
 * Android-Glue für [WifiTrustDecision] (2026-09-03, Ideenliste Punkt 5) — liest die aktuelle SSID
 * ([WifiCurrentSsidReader]), vergleicht sie gegen die vom Nutzer geführte Vertrauensliste
 * ([WifiTrustStorage]) und führt bei einem unbekannten Netz die eingestellte [WifiTrustReaction]
 * aus. Angestoßen periodisch von [WifiTrustWorker] und zusätzlich bei jedem Prozessstart
 * ([WifiTrustStartupWorker]) — dasselbe Muster wie
 * [de.ble1st.warden.cellsecurity.CellSecurityController].
 *
 * Anders als bei Cell-Security gibt es hier keine Baseline, die fortgeschrieben werden müsste —
 * die Vertrauensliste selbst *ist* der Vergleichsmaßstab, kein "letzter Messwert". Das bedeutet
 * aber auch: ein unbekanntes Netz wird bei **jedem** Prüflauf erneut gemeldet/reagiert, solange
 * das Gerät verbunden bleibt (keine "einmal pro Übergang"-Dämpfung wie bei
 * [de.ble1st.warden.cellsecurity.CellSecurityController]) — abgemildert durch die 15-Minuten-
 * Worker-Periode und dadurch, dass [WifiTrustReaction.NETZWERK_SPERREN] über
 * [NetLockdownController.arm] ohnehin idempotent ist. Für [WifiTrustReaction.NEUSTART]
 * (2026-09-05) bedeutet dieselbe fehlende Dämpfung: bleibt das Gerät mit demselben unbekannten
 * Netz verbunden, rebootet jeder folgende Prüflauf erneut — dasselbe bewusst in Kauf genommene
 * Verhalten wie bei [de.ble1st.warden.autoreboot.AutoRebootController]s unbeaufsichtigtem Gerät.
 */
class WifiTrustController(private val context: Context) {

    private val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)

    fun checkAndMaybeReact(isDebugBuild: Boolean) {
        val reaction = WifiTrustStorage.loadReaction(context) ?: return
        val trustedSsids = WifiTrustStorage.loadTrustedSsids(context)
        val outcome = WifiTrustDecision.evaluate(WifiCurrentSsidReader(context).currentSsid(), trustedSsids)
        if (outcome !is WifiTrustDecision.Outcome.Untrusted) return

        val logStore = wardenAuditLog(context)
        val reactionText = reactionText(reaction)
        logStore.append(Log.WARN, TAG, "Unbekanntes WLAN erkannt (${outcome.ssid}) — $reactionText")
        runCatching { WifiTrustNotifier(context).notify(outcome.ssid, reactionText) }
            .onFailure { Log.w(TAG, "Benachrichtigung fehlgeschlagen", it) }

        try {
            when (reaction) {
                WifiTrustReaction.NUR_MELDEN -> Unit
                WifiTrustReaction.NETZWERK_SPERREN -> {
                    val result = NetLockdownController(context).arm()
                    if (result !is NetLockdownController.ArmResult.Success) {
                        logStore.append(Log.ERROR, TAG, "Netz-Sperre-Reaktion fehlgeschlagen: $result")
                    }
                }
                WifiTrustReaction.NEUSTART -> {
                    val dpm = context.getSystemService(DevicePolicyManager::class.java)
                    if (dpm == null) {
                        logStore.append(Log.ERROR, TAG, "WLAN-Vertrauens-Neustart nicht ausführbar — DevicePolicyManager nicht verfügbar")
                    } else if (DestructiveCommandGuard.isExecutionAllowed(isDebugBuild)) {
                        dpm.reboot(admin)
                    } else {
                        logStore.append(Log.WARN, TAG, "WLAN-Vertrauens-Neustart unterdrückt — Debug-Build")
                    }
                }
            }
        } catch (e: Exception) {
            logStore.append(Log.ERROR, TAG, "WLAN-Vertrauens-Reaktion fehlgeschlagen: $e")
        }
    }

    private fun reactionText(reaction: WifiTrustReaction): String = when (reaction) {
        WifiTrustReaction.NUR_MELDEN -> "Es wurde nur protokolliert."
        WifiTrustReaction.NETZWERK_SPERREN -> "Die Netz-Sperre wurde aktiviert."
        WifiTrustReaction.NEUSTART -> "Das Gerät wird neu gestartet."
    }

    private companion object {
        const val TAG = "WifiTrust"
    }
}
