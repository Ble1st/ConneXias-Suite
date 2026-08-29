package de.ble1st.warden.profile

import android.content.Context
import android.util.Log
import de.ble1st.warden.WardenApplication
import de.ble1st.warden.domain.appmanagement.ThreatSeverity
import de.ble1st.warden.domain.profile.AutoProfileDecision
import de.ble1st.warden.wardenAuditLog
import java.time.LocalTime

/**
 * Android-Glue für [AutoProfileDecision] (2026-08-28) — angestoßen von [AutoProfileWorker].
 *
 * **Anwendung läuft über `ConcordBus.applyProfile`, nicht direkt über den
 * [de.ble1st.warden.registry.WardenProfileApplier]:** damit landet auch eine automatische
 * Umschaltung im Audit-Log und im Rate-Limit, genau wie ein manueller Tap — eine Automatik, die
 * an der Autorisierungspipeline vorbei schaltet, wäre in einem Sicherheitsprojekt der falsche
 * Sonderweg (s. `ConcordBus`-Klassendoc).
 *
 * **Kein Profil-Downgrade nach einem behandelten Fund ohne Zeitplan:** ist nur die
 * Bedrohungs-Eskalation eingeschaltet und der Fund verschwindet, bleibt `MAXIMAL` stehen, bis die
 * Besitzerin selbst zurückschaltet — das Zurücknehmen einer Verschärfung ist eine Entscheidung,
 * die niemand automatisch treffen sollte, während eventuell noch aufgeräumt wird.
 *
 * **Und kein Downgrade einer manuellen Härtung (2026-08-28, Befund Q-1):** deshalb geht neben
 * `lastAutoApplied` auch das zuletzt überhaupt angewendete Profil in die Entscheidung ein — die
 * Begründung und die Beispielabläufe stehen im Klassendoc von [AutoProfileDecision].
 */
class AutoProfileController(private val context: Context) {

    fun checkAndMaybeSwitch() {
        val config = AutoProfileStorage.load(context)
        if (!config.isEnabled) return

        val application = context.applicationContext as? WardenApplication ?: return
        val criticalFindingPresent = runCatching {
            application.concordBus.listSuspiciousAppFindings().any { it.severity == ThreatSeverity.CRITICAL }
        }.getOrElse { error ->
            // Fail-safe: lieber nicht eskalieren als aufgrund eines Lesefehlers — der Zeitplan
            // greift trotzdem weiter (Bedrohungslage gilt als "unbekannt", nicht als "kritisch").
            Log.w(TAG, "Verdachtsfunde nicht lesbar — Eskalationsprüfung übersprungen", error)
            false
        }

        val target = AutoProfileDecision.evaluate(
            config = config,
            minuteOfDay = LocalTime.now().let { it.hour * 60 + it.minute },
            criticalFindingPresent = criticalFindingPresent,
            lastAutoApplied = AutoProfileStorage.loadLastApplied(context),
            effectiveProfile = AutoProfileStorage.loadLastEffective(context),
        ) ?: return

        val logStore = wardenAuditLog(context)
        try {
            val result = application.concordBus.applyProfile(target)
            AutoProfileStorage.saveLastApplied(context, target)
            val reason = if (criticalFindingPresent && config.escalateOnCriticalThreat) {
                "kritischer Verdachtsfund"
            } else {
                "Zeitplan"
            }
            logStore.append(
                Log.INFO,
                TAG,
                "Profil automatisch auf ${target.label} geschaltet ($reason) — " +
                    "fehlgeschlagen: ${result.failed.size}, übersprungen: ${result.skipped.size}",
            )
        } catch (e: Exception) {
            logStore.append(Log.ERROR, TAG, "Automatische Profilumschaltung auf ${target.label} fehlgeschlagen: $e")
        }
    }

    private companion object {
        const val TAG = "AutoProfile"
    }
}
