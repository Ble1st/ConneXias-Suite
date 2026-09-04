package de.ble1st.warden.clipboard

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import de.ble1st.warden.domain.clipboard.ClipboardAccessEvent
import de.ble1st.warden.domain.clipboard.SensitiveContentDetector
import de.ble1st.warden.wardenAuditLog

/**
 * Framework-Seite von [ClipboardAccessibilityService] — hält den Service selbst dünn, dieselbe
 * Decision/Executor-Trennung wie [ClipboardGuardController] für Phase 1.
 *
 * **Zwei getrennte Senken für dasselbe Ereignis, bewusst unterschiedlich sensibel** (`docs/
 * design-clipboard-guard.md` Abschnitt 3.2.3/3.2.7): der tatsächliche Text landet ausschließlich
 * in [ClipboardAccessEventStore] (eigene Envelope-Datei, nur im ClipboardGuard-UI sichtbar). Der
 * echte, hash-verkettete `HashChainLogStore` bekommt nur eine Metadaten-Zeile — Zeitstempel und
 * App-Label, **nie den Text** — die ursprünglich in Abschnitt 3.2.3 vorgesehene Grenze gilt für
 * das Audit-Log unverändert, auch nachdem 3.2.6/3.2.7 den Umfang für die dedizierte Ereignisliste
 * erweitert haben.
 */
class ClipboardAccessController(private val context: Context) {

    /** Nie werfend: ein einzelnes fehlgeschlagenes Speichern eines Beobachtungs-Ereignisses ist
     * kein Fehlerzustand, der den AccessibilityService stören dürfte — derselbe Stil wie
     * [ClipboardGuardController.performClear]. */
    fun recordAccess(packageName: String, rawText: String) {
        val text = rawText.take(MAX_TEXT_LENGTH)
        val label = resolveAppLabel(packageName)
        val event = ClipboardAccessEvent(System.currentTimeMillis(), packageName, label, text)
        try {
            ClipboardAccessEventStore(ClipboardAccessEventStorage.buildEnvelopeFile(context)).append(listOf(event))
            wardenAuditLog(context).append(Log.INFO, TAG, "Zwischenablage-naher Zugriff erkannt: $label ($packageName)")
        } catch (e: Exception) {
            Log.w(TAG, "Cross-App-Zugriffsereignis nicht gespeichert", e)
        }
        // "Sensible-Einfügung-Alarm" (2026-09-03) — unabhängig vom obigen try/catch: ein
        // fehlgeschlagenes Speichern des Historieneintrags soll die Alarmierung selbst nicht
        // verhindern, beides sind unabhängige Senken für dasselbe Ereignis.
        SensitiveContentDetector.detect(text)?.let { category ->
            try {
                ClipboardSensitiveContentNotifier(context).notify(category, label)
                wardenAuditLog(context).append(
                    Log.WARN,
                    TAG,
                    "Sensibler Inhalt erkannt (${category.name.lowercase()}) beim Einfügen in $label ($packageName)",
                )
            } catch (e: Exception) {
                Log.w(TAG, "Sensible-Einfügung-Alarm fehlgeschlagen", e)
            }
        }
    }

    fun recentEvents(limit: Int): List<ClipboardAccessEvent> =
        ClipboardAccessEventStore(ClipboardAccessEventStorage.buildEnvelopeFile(context)).read().events.takeLast(limit)

    fun clearHistory() {
        ClipboardAccessEventStore(ClipboardAccessEventStorage.buildEnvelopeFile(context)).clear()
    }

    private fun resolveAppLabel(packageName: String): String =
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))).toString()
        }.getOrDefault(packageName)

    private companion object {
        const val TAG = "ClipboardAccess"

        /** Obergrenze pro erfasstem Ereignis — begrenzt sowohl die Dateigröße des Ringpuffers als
         * auch, wie viel ein einzelner extrem langer Einfügevorgang (z. B. ein versehentlich
         * eingefügter ganzer Dokumenttext) auf einmal in die verschlüsselte Historie schreibt. */
        const val MAX_TEXT_LENGTH = 2_000
    }
}
