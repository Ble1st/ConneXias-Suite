package de.ble1st.warden.clipboard

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import de.ble1st.warden.domain.clipboard.ClipboardAccessDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Produktionsversion von Phase 2 ("Signal 2", `docs/design-clipboard-guard.md` Abschnitt 3.2.6/
 * 3.2.7) — auf explizite Nutzerentscheidung 2026-09-03 ("volle Funktionalität, mit expliziter
 * UI-Aufklärung", Abschnitt 5 Frage 4, Option 3) gebaut, nachdem der Spike in 3.2.6 den vollen
 * Umfang (tatsächlicher Bildschirmtext, nicht nur ein Ereignis-Signal) bereits offengelegt hatte.
 *
 * **Was dieser Dienst tatsächlich kann, unverblümt:** jede Textänderung in jedem sichtbaren,
 * nicht als Passwortfeld markierten Eingabefeld jeder anderen App, deren Burst-Größe wie ein
 * Einfügevorgang aussieht (s. [ClipboardAccessDecision]s `MIN_BURST_CHARS`-Filter — eine
 * Heuristik, keine Garantie gegen z. B. schnelles Tippen oder Autovervollständigung). Das ist
 * exakt die Fähigkeitsklasse, die `de.ble1st.warden.appmanagement.AccessibilityServiceScanner` bei
 * *fremden* Apps als `WARNING`-Signal einstuft — Wardens eigenes Package ist von jedem Fund dieses
 * Scanners bereits strukturell ausgenommen (`SuspiciousAppScanDecision.ownPackageName`-Filter,
 * unverändert seit vor dieser Funktion), kein zusätzlicher Code hier nötig.
 *
 * **Selbstschutz vor Wardens eigener UI:** [ClipboardAccessDecision.evaluate] verwirft jedes
 * Ereignis mit `packageName == context.packageName` — dieser Dienst liest niemals Wardens eigene
 * PIN-Eingabe oder Einstellungs-Textfelder mit.
 *
 * **Läuft nie vor dem ersten Entsperren nach einem Neustart** (anders als
 * `WardenDeviceAdminReceiver`/`RegistryReconciliationReceiver`): ein `AccessibilityService`
 * gehört zur echten Nutzersitzung, nicht zum Direct-Boot-Systemstart — deshalb speichert
 * [ClipboardAccessEventStorage] bewusst *nicht* Device-Protected, s. dortiges Klassendoc.
 *
 * Aktivierung ist **kein** DPM-Silent-Grant (anders als z. B. `POST_NOTIFICATIONS`) — der Nutzer
 * muss den Dienst manuell unter Einstellungen → Bedienungshilfen freigeben, s.
 * [ClipboardAccessibilityStatus]. Wardens eigene App-Präferenz
 * ([ClipboardGuardStorage.isCrossAppMonitoringEnabled]) ist eine zweite, unabhängige Freigabe
 * *davor* — ein Nutzer, der die Systemfreigabe erteilt, aber Wardens eigenen Schalter nie
 * eingeschaltet hat, wird trotzdem nicht beobachtet.
 */
class ClipboardAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return

        val packageName = event.packageName?.toString()
        val text = event.text?.joinToString(separator = "") ?: ""
        // AccessibilityNodeInfo.isPassword ist der einzige Weg, ein Passwortfeld von hier aus zu
        // erkennen — `event.source` liefert bei Bedarf einen frischen Node-Snapshot;
        // `.recycle()` ist seit API 33 ein No-Op (minSdk 35 hier), deshalb bewusst nicht
        // aufgerufen, s. offizielle Deprecation-Notiz zu `AccessibilityNodeInfo.recycle()`.
        val isPassword = runCatching { event.source?.isPassword == true }.getOrDefault(false)

        val action = ClipboardAccessDecision.evaluate(
            monitoringEnabled = ClipboardGuardStorage.isCrossAppMonitoringEnabled(applicationContext),
            packageName = packageName,
            ownPackageName = applicationContext.packageName,
            isPassword = isPassword,
            addedCount = event.addedCount,
            text = text,
        )
        val capture = action as? ClipboardAccessDecision.Action.Capture ?: return
        val pkg = packageName ?: return
        // Dateizugriff (EnvelopeFile-Verschlüsselung) gehört nicht auf den Callback-Thread des
        // Accessibility-Frameworks — derselbe Grund wie `onSecurityLogsAvailable`s
        // `goAsync()`-Umstellung (CLAUDE.md), nur ohne `goAsync`-Äquivalent hier: der Service lebt
        // ohnehin so lange wie die Bedienungshilfe aktiv ist, ein eigener `CoroutineScope` reicht.
        serviceScope.launch {
            runCatching { ClipboardAccessController(applicationContext).recordAccess(pkg, capture.text) }
                .onFailure { Log.w(TAG, "Cross-App-Zugriffsereignis nicht verarbeitet", it) }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private companion object {
        const val TAG = "ClipboardAccessSvc"
    }
}
