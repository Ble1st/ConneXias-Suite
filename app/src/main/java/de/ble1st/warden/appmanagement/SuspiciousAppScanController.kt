package de.ble1st.warden.appmanagement

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import de.ble1st.warden.domain.appmanagement.ActivationTransitionDecision
import de.ble1st.warden.domain.appmanagement.SuspiciousAppNotificationActionDecision
import de.ble1st.warden.domain.appmanagement.AppFreezeGuard
import de.ble1st.warden.domain.appmanagement.SigningCertChangeDecision
import de.ble1st.warden.domain.appmanagement.SuspiciousAppFinding
import de.ble1st.warden.domain.appmanagement.SuspiciousAppScanDecision
import de.ble1st.warden.domain.appmanagement.SuspiciousSignal
import de.ble1st.warden.wardenAuditLog

/**
 * Milestone "Automatisches Einfrieren verdächtiger Apps", seit "Manifest-Scan + Sofort-
 * Benachrichtigung" (2026-08-21) und "weitere Funktionen für den Sicherheitsscanner" (2026-08-22)
 * erweitert. Verkabelt inzwischen acht Verdachtssignale ([SuspiciousSignal]-Klassendoc listet
 * alle) über die reine Entscheidungslogik [SuspiciousAppScanDecision] mit
 * [AppManagementController]s bereits bestehendem, geschützten Freeze-Pfad — kein zweiter
 * Freeze-Mechanismus, derselbe `AppFreezeGuard`-Schutz (Warden nie einfrierbar) gilt automatisch
 * mit, unabhängig von der hier zusätzlich geprüften Ausschlussmenge.
 *
 * **Zwei unabhängige Reaktionswege auf einen Fund:**
 * - [scanAndEnforce] — stilles Auto-Einfrieren, **bewusst opt-in** ([SuspiciousAppScanStore
 *   .isEnabled], Default `false`): mutiert den Gerätezustand ohne Nutzerbestätigung pro
 *   Einzelfall, deshalb nur, wenn explizit aktiviert.
 * - [notifyNewFindings] — Sicherheitsbenachrichtigung mit Sofort-Aktionen ("Einfrieren"/
 *   "Deinstallieren", s. [SuspiciousAppNotifier]/[SuspiciousAppActionReceiver]), läuft **immer**,
 *   unabhängig von [isEnabled] (dieselbe Transparenz-Haltung wie [scan]) — eine Benachrichtigung
 *   mit expliziten Knöpfen fragt vor jeder Aktion, ist also nie überraschend, anders als das
 *   stille Auto-Einfrieren. Seit Feature 6 ("diff-basierte Benachrichtigung") nur noch für
 *   tatsächlich *neue oder veränderte* Funde ([SuspiciousAppNotifiedStore]) — ein unverändert
 *   offener Fund wird nicht bei jedem 15-Minuten-Lauf erneut gepostet.
 * - [runImmediateScan] — beide Wege sofort statt beim nächsten periodischen Lauf, für den
 *   manuellen "Jetzt scannen"-Button (Feature 10).
 *
 * **[setEnabled]`(true)` löst seit 2026-08-25 selbst einen sofortigen [scanAndEnforce]-Lauf aus**
 * (live verifiziert: vorher ließ sich ein bereits bestehender, noch nicht durchgesetzter Fund
 * trotz frisch aktiviertem Schalter bis zum nächsten periodischen/manuellen Scan weiter öffnen —
 * ein Timing-Fenster, kein Sicherheitsloch, aber überraschend, weil das Umschalten optisch
 * sofortige Wirkung suggeriert). `setEnabled(false)` löst bewusst nichts aus — Deaktivieren soll
 * ein bereits eingefrorenes Ergebnis nicht rückgängig machen, das bleibt dem expliziten
 * "Entfrieren" in [AppManagementController]/[trust] vorbehalten.
 *
 * **Systemapps sind strukturell ausgenommen** ([InstalledAppLister.isSystemApp], durchgereicht an
 * [SuspiciousAppScanDecision.evaluate]) — ein vorinstallierter Barrierefreiheits-Dienst (z. B.
 * ein herstellereigener TalkBack-Ersatz) soll nicht versehentlich eingefroren werden; das
 * eigentliche Bedrohungsmodell (Social-Engineering-App fordert Admin/Accessibility) betrifft
 * praktisch immer nachträglich vom Nutzer installierte, nicht-System-Apps.
 *
 * **[scan] hat seit Feature 4/5 einen dokumentierten Nebeneffekt:** es aktualisiert die
 * Signatur-/Aktivierungs-Baselines ([SigningCertHistoryStore]/[ActivationHistoryStore]) für den
 * *nächsten* Aufruf — die ursprüngliche "reine Erkennung, ohne etwas zu verändern"-Eigenschaft
 * gilt jetzt nur noch bezogen auf den Gerätezustand (DPM/PackageManager/Accessibility), nicht
 * mehr auf Wardens eigenen Baseline-Cache. Bewusst in Kauf genommen statt einer separaten
 * "commit"-Methode: jeder Aufrufer (UI-Öffnen, periodischer Worker, manueller Scan) soll dieselbe,
 * konsistente Baseline vorwärtsbewegen — ein Fenster, in dem zwei Aufrufer unterschiedliche
 * Baselines sähen, wäre die schlechtere Alternative.
 *
 * [trust] friert nicht nur nichts mehr automatisch ein, sondern hebt ein bereits erfolgtes
 * automatisches Einfrieren sofort wieder auf und zieht eine offene Benachrichtigung zurück —
 * sonst müsste der Nutzer zusätzlich noch manuell über [AppManagementController] entfrieren, und
 * ein nächster Scan-Lauf könnte theoretisch dazwischenfunken, bevor die Vertrauensentscheidung
 * persistiert ist.
 */
class SuspiciousAppScanController(
    private val context: Context,
    private val appLister: InstalledAppLister,
    private val adminReader: DeviceAdminCapabilityScanner,
    private val accessibilityScanner: AccessibilityServiceScanner,
    private val overlayScanner: OverlayPermissionScanner,
    private val notificationListenerScanner: NotificationListenerScanner,
    private val installSourceScanner: InstallSourceScanner,
    private val signingCertReader: SigningCertReader,
    private val signingCertHistoryStore: SigningCertHistoryStore,
    private val activeCapabilityReader: ActiveCapabilityReader,
    private val activationHistoryStore: ActivationHistoryStore,
    private val notifiedStore: SuspiciousAppNotifiedStore,
    private val store: SuspiciousAppScanStore,
    private val appManagementController: AppManagementController,
    private val notifier: SuspiciousAppNotifier,
    private val uninstaller: AppUninstaller,
    private val dataWiper: AppDataWiper,
    private val ownPackageName: String,
    private val protectedPackageNames: Set<String>,
) {

    private val logStore by lazy { wardenAuditLog(context) }

    fun isEnabled(): Boolean = store.isEnabled()

    fun setEnabled(enabled: Boolean) {
        store.setEnabled(enabled)
        logStore.append(Log.WARN, TAG, "Verdachtsscanner ${if (enabled) "aktiviert" else "deaktiviert"}")
        // Ohne dies bleibt ein beim Aktivieren bereits bestehender Fund bis zum nächsten
        // periodischen/manuellen Scan unangetastet (weiterhin offen/öffenbar) — s. Klassendoc.
        if (enabled) {
            scanAndEnforce()
        }
    }

    /** Evaluate + commit baselines. UI list reads use this; scheduled/immediate passes use
     * [prepareScan] so enforce/notify see the same transition signals before baselines move. */
    fun scan(): List<SuspiciousAppFinding> {
        val prepared = prepareScan()
        prepared.commitBaselines()
        return prepared.findings
    }

    /** Wie [scan], aber angereichert mit Label/Ist-eingefroren für die UI. */
    fun scanWithDetails(): List<SuspiciousAppFindingInfo> = enrich(scan())

    /** Erkennung + Durchsetzung. Läuft nur, wenn [isEnabled]. Baselines werden nach dem
     * Enforce-Schritt geschrieben, damit Transition-Signale nicht vor der Notification verloren
     * gehen, wenn der Aufrufer danach noch [notifyNewFindings] auf derselben Fundliste ausführt. */
    fun scanAndEnforce(): List<SuspiciousAppFinding> {
        if (!isEnabled()) return emptyList()
        val prepared = prepareScan()
        enforce(prepared.findings)
        prepared.commitBaselines()
        return prepared.findings
    }

    /** Sicherheitsbenachrichtigung für neue/veränderte Funde. Ohne Argument erneut scannen
     * (UI/Ad-hoc); Worker und Sofort-Scan übergeben die bereits evaluierte Liste. */
    fun notifyNewFindings(findings: List<SuspiciousAppFindingInfo> = scanWithDetails()) {
        for (finding in findings) {
            if (finding.frozen) continue
            if (notifiedStore.lastNotifiedBitmask(finding.packageName) == finding.signalsBitmask) continue
            notifier.notify(finding)
            notifiedStore.recordNotified(finding.packageName, finding.signalsBitmask)
        }
    }

    /** Ein Evaluate, dann optional Enforce, Notify, dann Baseline-Commit — derselbe Lauf für
     * Worker und den manuellen "Jetzt scannen"-Button. */
    fun runImmediateScan(): List<SuspiciousAppFindingInfo> {
        val prepared = prepareScan()
        if (isEnabled()) {
            enforce(prepared.findings)
        }
        val detailed = enrich(prepared.findings)
        notifyNewFindings(detailed)
        prepared.commitBaselines()
        return detailed
    }

    private class PreparedScan(
        val findings: List<SuspiciousAppFinding>,
        private val commit: () -> Unit,
    ) {
        fun commitBaselines() = commit()
    }

    private fun prepareScan(): PreparedScan {
        val installedApps = appLister.listInstalledApps()
        val systemPackageNames = installedApps.filter { it.isSystemApp }.map { it.packageName }.toSet()
        val nonSystemPackageNames = installedApps.map { it.packageName }.toSet() - systemPackageNames

        val currentActiveAdmins = activeCapabilityReader.activeDeviceAdminPackageNames()
        val currentActiveAccessibility = activeCapabilityReader.activeAccessibilityServicePackageNames()
        val newlyActivatedAdmins = ActivationTransitionDecision.evaluate(
            activationHistoryStore.previouslyActiveDeviceAdmins(),
            currentActiveAdmins,
        )
        val newlyActivatedAccessibility = ActivationTransitionDecision.evaluate(
            activationHistoryStore.previouslyActiveAccessibilityServices(),
            currentActiveAccessibility,
        )

        val currentFingerprints = nonSystemPackageNames
            .mapNotNull { pkg -> signingCertReader.fingerprintFor(pkg)?.let { pkg to it } }
            .toMap()
        val signingCertChanged = SigningCertChangeDecision.evaluate(
            signingCertHistoryStore.fingerprints(),
            currentFingerprints,
        )

        val findings = SuspiciousAppScanDecision.evaluate(
            deviceAdminPackageNames = adminReader.declaredDeviceAdminPackageNames(),
            accessibilityPackageNames = accessibilityScanner.declaredAccessibilityServicePackageNames(),
            overlayPackageNames = overlayScanner.declaredOverlayPermissionPackageNames(),
            notificationListenerPackageNames = notificationListenerScanner.declaredNotificationListenerPackageNames(),
            unknownInstallSourcePackageNames = installSourceScanner.unknownInstallSourcePackageNames(nonSystemPackageNames),
            signingCertChangedPackageNames = signingCertChanged,
            deviceAdminNewlyActivatedPackageNames = newlyActivatedAdmins,
            accessibilityNewlyActivatedPackageNames = newlyActivatedAccessibility,
            ownPackageName = ownPackageName,
            protectedPackageNames = protectedPackageNames,
            systemPackageNames = systemPackageNames,
            trustedPackageNames = store.trustedPackages(),
        )
        return PreparedScan(findings) {
            activationHistoryStore.recordActiveDeviceAdmins(currentActiveAdmins)
            activationHistoryStore.recordActiveAccessibilityServices(currentActiveAccessibility)
            currentFingerprints.forEach { (pkg, fingerprint) -> signingCertHistoryStore.record(pkg, fingerprint) }
        }
    }

    private fun enrich(findings: List<SuspiciousAppFinding>): List<SuspiciousAppFindingInfo> {
        val labels = appLister.listInstalledApps().associate { it.packageName to it.label }
        return findings.map { finding ->
            SuspiciousAppFindingInfo(
                packageName = finding.packageName,
                label = labels[finding.packageName] ?: finding.packageName,
                signalsBitmask = SuspiciousSignal.toBitmask(finding.signals),
                frozen = appManagementController.isFrozen(finding.packageName),
            )
        }
    }

    private fun enforce(findings: List<SuspiciousAppFinding>) {
        for (finding in findings) {
            if (appManagementController.isFrozen(finding.packageName)) continue
            appManagementController.setFrozen(finding.packageName, true)
            val actuallyFrozen = appManagementController.isFrozen(finding.packageName)
            logStore.append(
                if (actuallyFrozen) Log.WARN else Log.ERROR,
                TAG,
                "Automatisch eingefroren (Signale=${finding.signals}): pkg=${finding.packageName} " +
                    "success=$actuallyFrozen",
            )
            if (actuallyFrozen) {
                notifier.cancel(finding.packageName)
                notifiedStore.clear(finding.packageName)
            }
        }
    }

    fun trust(packageName: String) {
        store.trust(packageName)
        appManagementController.setFrozen(packageName, false)
        notifier.cancel(packageName)
        notifiedStore.clear(packageName)
        logStore.append(Log.WARN, TAG, "Als vertrauenswürdig markiert (Verdachtsscanner): pkg=$packageName")
    }

    /** Von [SuspiciousAppActionReceiver] aufgerufen — Nutzer hat "Einfrieren" in der
     * Sicherheitsbenachrichtigung angetippt. [AppManagementController.setFrozen] prüft
     * [AppFreezeGuard] bereits selbst; scheitert der Aufruf, wird das der Nutzerin ehrlich
     * mitgeteilt statt stillschweigend zu verpuffen.
     *
     * Live-getestet (2026-08-21, kontrollierter A/B-Test mit zwei Wegwerf-Apps): Android
     * blockiert `setApplicationHidden()` bereits, wenn eine App im Manifest überhaupt einen
     * `DeviceAdminReceiver` *deklariert* — unabhängig davon, ob dieser je aktiviert wurde. Das
     * betrifft also gerade den [SuspiciousSignal.EXTRA_DEVICE_ADMIN]-Fund, den dieser Scanner
     * proaktiv (vor Aktivierung) erkennen soll — Einfrieren bleibt für diese Kategorie deshalb
     * strukturell meist nicht möglich, nur Deinstallieren oder eine manuelle Deaktivierung des
     * Geräteadmin-Status. Für [SuspiciousSignal.ACCESSIBILITY_SERVICE_DECLARED] gilt diese
     * Einschränkung *nicht* (im selben Test bestätigt) — dort friert Einfrieren zuverlässig ein.
     * Zusätzlich bekannt: `DEBUGGABLE`-Ziele lassen sich ebenfalls nicht einfrieren. */
    fun handleFreezeAction(packageName: String) {
        if (!authorizeNotificationAction(packageName)) return
        val result = appManagementController.setFrozen(packageName, true)
        if (result) {
            notifier.cancel(packageName)
            notifiedStore.clear(packageName)
        } else {
            notifier.showActionFailed(
                packageName,
                "Einfrieren fehlgeschlagen — Android blockiert das für Apps, die im Manifest " +
                    "eine Geräteadmin-Fähigkeit deklarieren (auch ohne sie je aktiviert zu " +
                    "haben) oder die zum Testen/Debuggen markiert sind. Bitte stattdessen " +
                    "deinstallieren oder den Geräteadmin-Status manuell unter Einstellungen > " +
                    "Geräteadministrator-Apps prüfen.",
            )
        }
    }

    /** Von [SuspiciousAppActionReceiver] aufgerufen — Nutzer hat "Deinstallieren" angetippt.
     * [AppFreezeGuard] wird hier explizit geprüft (anders als beim Einfrieren gibt es sonst
     * keinen zweiten Aufrufer, der das schon täte) — Warden/geschützte Pakete lassen sich nie
     * über diesen Pfad entfernen. Das eigentliche Ergebnis kommt asynchron über
     * [handleUninstallResult] zurück, s. [AppUninstaller]-Klassendoc. */
    fun handleUninstallAction(packageName: String) {
        if (!authorizeNotificationAction(packageName)) return
        if (AppFreezeGuard.isProtected(packageName, ownPackageName, protectedPackageNames)) {
            logStore.append(Log.WARN, TAG, "Deinstallation abgelehnt (geschütztes Ziel): pkg=$packageName")
            return
        }
        uninstaller.uninstall(packageName)
    }

    /** Anders als [handleFreezeAction] noch nicht live gegen eine bloß deklarierte (nie
     * aktivierte) Geräteadmin-Fähigkeit getestet — die Meldung unten geht deshalb konservativ
     * vom bekannten, dokumentierten Android-Verhalten aus (aktive Geräteadmins lassen sich nicht
     * ohne vorherige Deaktivierung deinstallieren), statt eine ungetestete Behauptung über den
     * bloß-deklariert-Fall zu machen. */
    fun handleUninstallResult(packageName: String, success: Boolean) {
        if (success) {
            notifier.cancel(packageName)
            notifiedStore.clear(packageName)
            logStore.append(Log.WARN, TAG, "Deinstalliert (Sicherheitsbenachrichtigung): pkg=$packageName")
        } else {
            logStore.append(Log.ERROR, TAG, "Deinstallation fehlgeschlagen: pkg=$packageName")
            notifier.showActionFailed(
                packageName,
                "Deinstallation fehlgeschlagen — die App ist vermutlich bereits aktiver " +
                    "Geräteadministrator. Bitte manuell unter Einstellungen > Geräteadministrator-" +
                    "Apps deaktivieren, dann erneut versuchen.",
            )
        }
    }

    /** Tier 3 ("App-Kontrolle", 2026-08-22) — von [SuspiciousAppActionReceiver] aufgerufen, Nutzer
     * hat "Daten löschen" in der Sicherheitsbenachrichtigung angetippt. [AppFreezeGuard] wird
     * explizit geprüft, derselbe Schutz wie bei [handleUninstallAction] — Warden/geschützte Pakete
     * lassen sich nie über diesen Pfad treffen. Anders als Deinstallieren unabhängig vom
     * Geräteadmin-Aktivierungszustand des Ziels (s. [AppDataWiper]-Klassendoc). */
    fun handleClearDataAction(packageName: String) {
        if (!authorizeNotificationAction(packageName)) return
        if (AppFreezeGuard.isProtected(packageName, ownPackageName, protectedPackageNames)) {
            logStore.append(Log.WARN, TAG, "Datenlöschung abgelehnt (geschütztes Ziel): pkg=$packageName")
            return
        }
        dataWiper.clear(packageName) { success ->
            if (success) {
                notifier.cancel(packageName)
                notifiedStore.clear(packageName)
                logStore.append(Log.WARN, TAG, "App-Daten gelöscht (Sicherheitsbenachrichtigung): pkg=$packageName")
            } else {
                logStore.append(Log.ERROR, TAG, "Datenlöschung fehlgeschlagen: pkg=$packageName")
                notifier.showActionFailed(
                    packageName,
                    "Datenlöschung fehlgeschlagen — bitte stattdessen einfrieren oder deinstallieren.",
                )
            }
        }
    }

    /**
     * Lock-screen shade and stale notifications must not freeze / wipe / silently
     * uninstall. The original alert stays up when the device is still locked so the
     * owner can retry after unlock. [handleUninstallResult] is not gated — that
     * broadcast is the PackageInstaller callback for an already started uninstall.
     */
    private fun authorizeNotificationAction(packageName: String): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val locked = keyguard?.isDeviceLocked == true || keyguard?.isKeyguardLocked == true
        val isOpenFinding = notifiedStore.lastNotifiedBitmask(packageName) != null
        if (SuspiciousAppNotificationActionDecision.allowDestructiveAction(locked, isOpenFinding)) {
            return true
        }
        if (locked) {
            logStore.append(Log.WARN, TAG, "Notification action blocked (device locked): pkg=$packageName")
            return false
        }
        logStore.append(Log.WARN, TAG, "Notification action blocked (not an open finding): pkg=$packageName")
        notifier.showActionFailed(
            packageName,
            "Keine aktuelle Warnung für dieses Paket — Aktion verworfen.",
        )
        return false
    }

    companion object {
        private const val TAG = "SuspiciousAppScan"
    }
}
