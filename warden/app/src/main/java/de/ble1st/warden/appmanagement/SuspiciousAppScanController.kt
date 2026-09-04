package de.ble1st.warden.appmanagement

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import de.ble1st.warden.R
import de.ble1st.warden.domain.appmanagement.ActivationTransitionDecision
import de.ble1st.warden.domain.appmanagement.SuspiciousAppNotificationActionDecision
import de.ble1st.warden.domain.appmanagement.AppFreezeGuard
import de.ble1st.warden.domain.appmanagement.SigningCertChangeDecision
import de.ble1st.warden.domain.appmanagement.SuspiciousAppFinding
import de.ble1st.warden.domain.appmanagement.SuspiciousAppScanDecision
import de.ble1st.warden.domain.appmanagement.SuspiciousSignal
import de.ble1st.warden.domain.appmanagement.ThreatSeverity
import de.ble1st.warden.domain.appmanagement.PermissionEscalationDecision
import de.ble1st.warden.domain.appmanagement.VersionDowngradeDecision
import de.ble1st.warden.domain.pin.WardenLockTaskAutoEngageDecision
import de.ble1st.warden.pin.WardenLockTaskAutoEngageStore
import de.ble1st.warden.pin.WardenLockTaskDrillStorage
import de.ble1st.warden.pin.WardenLockTaskPendingEngageStore
import de.ble1st.warden.registry.DeviceLockdownBundle
import de.ble1st.warden.wardenAuditLog

/**
 * Milestone "Automatisches Einfrieren verdächtiger Apps", seit "Manifest-Scan + Sofort-
 * Benachrichtigung" (2026-08-21), "weitere Funktionen für den Sicherheitsscanner" (2026-08-22)
 * und "LockMode/Threat-Protection-Ausbau" (2026-08-25) erweitert. Verkabelt inzwischen neun
 * Verdachtssignale ([SuspiciousSignal]-Klassendoc listet alle) über die reine Entscheidungslogik
 * [SuspiciousAppScanDecision] mit
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
 *   manuellen "Jetzt scannen"-Button (Feature 10) **und** (seit 2026-08-25) für
 *   [PackageChangeReceiver], der jede Paketänderung sofort einen Lauf auslösen lässt statt bis zu
 *   15 Minuten auf [SuspiciousAppScanWorker] zu warten.
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
 * **Wer die Baselines vorrücken darf (Korrektur 2026-08-28, aus der Code-/Sicherheitsanalyse):**
 * ausschließlich [scanAndEnforce] und [runImmediateScan] — also genau die beiden Wege, die einen
 * Fund auch *behandeln* (durchsetzen und/oder benachrichtigen). [scan]/[scanWithDetails] werten
 * nur noch aus.
 *
 * Vorher committete [scan] selbst, mit der Begründung, alle Aufrufer sollten dieselbe Baseline
 * teilen. Das war der falsche Kompromiss: die vier `CRITICAL`-Signale
 * ([SuspiciousSignal.SIGNING_CERT_CHANGED], [SuspiciousSignal.DEVICE_ADMIN_NEWLY_ACTIVATED],
 * [SuspiciousSignal.ACCESSIBILITY_SERVICE_NEWLY_ACTIVATED], [SuspiciousSignal.VERSION_DOWNGRADED])
 * sind **Einmal-Signale** — sie existieren nur, solange die gespeicherte Baseline vom Ist-Zustand
 * abweicht. Da [scanWithDetails] über [de.ble1st.warden.bus.ConcordBus.listSuspiciousAppFindings]
 * sowohl vom Dashboard (bei jedem Öffnen) als auch von
 * [de.ble1st.warden.profile.AutoProfileController] (alle 15 Minuten) aufgerufen wird, verbrauchte
 * in aller Regel ein *Lesepfad* das Signal, bevor [SuspiciousAppScanWorker] es überhaupt sah:
 * keine Benachrichtigung, kein Auto-Einfrieren, kein Lock-Task-Auto-Engage für einen
 * Zertifikatswechsel — und beim Auto-Profil eine Eskalation auf `MAXIMAL`, die beim nächsten Lauf
 * mangels Fund gleich wieder zurückfiel.
 *
 * Der befürchtete Nachteil (zwei Aufrufer sehen unterschiedliche Baselines) tritt dadurch nicht
 * ein: ein reiner Lesepfad *verändert* jetzt nichts, er sieht denselben offenen Fund so lange, bis
 * ein behandelnder Lauf ihn quittiert. Genau das ist gewollt — ein noch nicht behandeltes Signal
 * soll sichtbar bleiben.
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
    private val versionReader: PackageVersionReader,
    private val versionHistoryStore: VersionHistoryStore,
    private val dangerousPermissionReader: DangerousPermissionReader,
    private val permissionHistoryStore: PermissionHistoryStore,
    private val activeCapabilityReader: ActiveCapabilityReader,
    private val activationHistoryStore: ActivationHistoryStore,
    private val notifiedStore: SuspiciousAppNotifiedStore,
    private val store: SuspiciousAppScanStore,
    private val appManagementController: AppManagementController,
    private val notifier: SuspiciousAppNotifier,
    private val uninstaller: AppUninstaller,
    private val dataWiper: AppDataWiper,
    private val permissionRevoker: DangerousPermissionRevoker,
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

    /**
     * Reine Auswertung **ohne** Baseline-Commit — s. den "Wer die Baselines vorrücken darf"-Absatz
     * im Klassendoc. Nur [scanAndEnforce] und [runImmediateScan] committen, weil nur sie den Fund
     * auch tatsächlich behandeln (durchsetzen + benachrichtigen). Jeder reine Lesepfad (UI-Liste,
     * [de.ble1st.warden.profile.AutoProfileController]) darf ein Transition-Signal sehen, aber
     * nicht verbrauchen.
     */
    fun scan(): List<SuspiciousAppFinding> = prepareScan().findings

    /** Wie [scan], aber angereichert mit Label/Ist-eingefroren für die UI — ebenfalls ohne
     * Baseline-Commit. Ein einziges [prepareScan] für beides (Befund Q-9, 2026-08-29): vorher
     * `enrich(scan())`, was die Paketliste zweimal holte. */
    fun scanWithDetails(): List<SuspiciousAppFindingInfo> =
        prepareScan().let { prepared -> enrich(prepared.findings, prepared.labels) }

    /** Erkennung + Durchsetzung. Läuft nur, wenn [isEnabled]. Baselines werden nach dem
     * Enforce-Schritt geschrieben, damit Transition-Signale nicht vor der Notification verloren
     * gehen, wenn der Aufrufer danach noch [notifyNewFindings] auf derselben Fundliste ausführt. */
    fun scanAndEnforce(): List<SuspiciousAppFinding> {
        if (!isEnabled()) return emptyList()
        val prepared = prepareScan()
        enforce(prepared.findings)
        prepared.commitBaselines()
        // Befund Q-9 (2026-08-29), s. runImmediateScan.
        SuspiciousAppThreatLevelStore.record(context, prepared.findings)
        return prepared.findings
    }

    /** Sicherheitsbenachrichtigung für neue/veränderte Funde. Ohne Argument erneut scannen
     * (UI/Ad-hoc); Worker und Sofort-Scan übergeben die bereits evaluierte Liste.
     *
     * **Seit "LockMode/Threat-Protection-Ausbau" (2026-08-25) zusätzlich der einzige Auslöser für
     * ein automatisches Lock-Task-Engage** (s. [WardenLockTaskAutoEngageDecision]-Klassendoc):
     * nur *neue/veränderte* Funde zählen, kein bereits bekannter, unveränderter Fund löst bei
     * jedem 15-Minuten-Lauf erneut eine Anforderung aus — dieselbe diff-basierte Begründung wie
     * bei der Benachrichtigung selbst, direkt darüber. */
    fun notifyNewFindings(findings: List<SuspiciousAppFindingInfo> = scanWithDetails()) {
        val autoEngageEligible = WardenLockTaskDrillStorage.isConfirmed(context) &&
            WardenLockTaskAutoEngageStore.isEnabled(context)
        // Nur bei Bedarf abgefragt (echter DPM-Aufruf) — die beiden lokalen Opt-ins oben sind
        // billig und filtern den häufigen Fall (nichts davon aktiviert) vorher heraus.
        val lockdownArmed by lazy { runCatching { DeviceLockdownBundle.build(context).isActive() }.getOrDefault(false) }

        for (finding in findings) {
            if (finding.frozen) continue
            if (notifiedStore.lastNotifiedBitmask(finding.packageName) == finding.signalsBitmask) continue
            notifier.notify(finding)
            notifiedStore.recordNotified(finding.packageName, finding.signalsBitmask)

            if (autoEngageEligible &&
                WardenLockTaskAutoEngageDecision.shouldRequestEngage(
                    severity = finding.severity,
                    drillConfirmed = true,
                    lockdownArmed = lockdownArmed,
                    autoEngageEnabled = true,
                )
            ) {
                WardenLockTaskPendingEngageStore.requestEngage(
                    context,
                    reason = context.getString(R.string.suspicious_app_scan_critical_finding_reason, finding.label, finding.packageName),
                )
                logStore.append(Log.WARN, TAG, "Lock-Task-Auto-Engage angefordert: pkg=${finding.packageName}")
            }
        }
    }

    /** Ein Evaluate, dann optional Enforce, Notify, dann Baseline-Commit — derselbe Lauf für
     * Worker und den manuellen "Jetzt scannen"-Button. */
    fun runImmediateScan(): List<SuspiciousAppFindingInfo> {
        val prepared = prepareScan()
        if (isEnabled()) {
            enforce(prepared.findings)
        }
        val detailed = enrich(prepared.findings, prepared.labels)
        notifyNewFindings(detailed)
        prepared.commitBaselines()
        // Befund Q-9 (2026-08-29): der Bedrohungsstand wird hier festgehalten, damit
        // AutoProfileController ihn lesen kann, statt alle 15 Minuten einen zweiten vollständigen
        // Paket-Scan auszulösen — s. SuspiciousAppThreatLevelStore-Klassendoc.
        SuspiciousAppThreatLevelStore.record(context, prepared.findings)
        return detailed
    }

    /**
     * [labels] wird aus dem *bereits* in [prepareScan] geholten `listInstalledApps()`-Ergebnis
     * gebildet und an [enrich] durchgereicht (Befund Q-9, 2026-08-29). Vorher rief `enrich()` die
     * Paketliste ein zweites Mal ab, obwohl `prepareScan()` sie unmittelbar davor schon hatte —
     * ein voller `QUERY_ALL_PACKAGES`-Durchlauf pro Scan zu viel, und der teuerste Einzelschritt
     * des ganzen Laufs.
     */
    private class PreparedScan(
        val findings: List<SuspiciousAppFinding>,
        val labels: Map<String, String>,
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

        val currentVersionCodes = nonSystemPackageNames
            .mapNotNull { pkg -> versionReader.versionCodeFor(pkg)?.let { pkg to it } }
            .toMap()
        val versionDowngraded = VersionDowngradeDecision.evaluate(
            versionHistoryStore.versionCodes(),
            currentVersionCodes,
        )

        val currentDangerousPermissions = nonSystemPackageNames
            .associateWith { pkg -> dangerousPermissionReader.dangerousPermissionsFor(pkg) }
        val permissionEscalated = PermissionEscalationDecision.evaluate(
            permissionHistoryStore.dangerousPermissions(),
            currentDangerousPermissions,
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
            versionDowngradedPackageNames = versionDowngraded,
            permissionEscalatedPackageNames = permissionEscalated,
            ownPackageName = ownPackageName,
            protectedPackageNames = protectedPackageNames,
            systemPackageNames = systemPackageNames,
            trustedPackageNames = store.trustedPackages(),
        )
        return PreparedScan(
            findings = findings,
            labels = installedApps.associate { it.packageName to it.label },
        ) {
            activationHistoryStore.recordActiveDeviceAdmins(currentActiveAdmins)
            activationHistoryStore.recordActiveAccessibilityServices(currentActiveAccessibility)
            currentFingerprints.forEach { (pkg, fingerprint) -> signingCertHistoryStore.record(pkg, fingerprint) }
            currentVersionCodes.forEach { (pkg, versionCode) -> versionHistoryStore.record(pkg, versionCode) }
            currentDangerousPermissions.forEach { (pkg, permissions) -> permissionHistoryStore.record(pkg, permissions) }
        }
    }

    /** [labels] kommt aus [PreparedScan] — s. dessen Klassendoc (Befund Q-9): nie selbst
     * `listInstalledApps()` aufrufen, der Aufrufer hat die Liste bereits. */
    private fun enrich(
        findings: List<SuspiciousAppFinding>,
        labels: Map<String, String>,
    ): List<SuspiciousAppFindingInfo> {
        return findings.map { finding ->
            SuspiciousAppFindingInfo(
                packageName = finding.packageName,
                label = labels[finding.packageName] ?: finding.packageName,
                signalsBitmask = SuspiciousSignal.toBitmask(finding.signals),
                frozen = appManagementController.isFrozen(finding.packageName),
            )
        }
    }

    /**
     * Automatisches Einfrieren wirkt nur ab [ThreatSeverity.WARNING] aufwärts (2026-08-28, aus
     * der Code-/Sicherheitsanalyse, Befund S-7). Ein Fund entsteht bereits bei einem reinen
     * `INFO`-Signal ([SuspiciousSignal.UNKNOWN_INSTALL_SOURCE]/
     * [SuspiciousSignal.OVERLAY_PERMISSION_DECLARED], s. [ThreatSeverity]-Klassendoc) — auf einem
     * Device-Owner-Gerät, das Warden selbst als rohe APK über GitHub Releases erreicht (also
     * zwangsläufig sideload-installiert wurde), friert der Scanner ohne diese Schwelle praktisch
     * jede seitlich installierte App ein, sobald er eingeschaltet wird — inklusive legitimer.
     * `INFO`-Funde bleiben unverändert sichtbar (Dashboard, Benachrichtigung mit Aktionsknöpfen),
     * lösen nur keine automatische Aktion mehr aus.
     */
    private fun enforce(findings: List<SuspiciousAppFinding>) {
        for (finding in findings) {
            if (ThreatSeverity.highest(finding.signals) < ThreatSeverity.WARNING) continue
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

            // "Automated Incident Response" (2026-08-25, s. DangerousPermissionRevoker-Klassendoc)
            // — unabhängig davon, ob das Einfrieren selbst geglückt ist (bekannte OS-Lücke für
            // Geräteadmin-deklarierende/debuggbare Ziele, s. AppFreezeManager-Klassendoc):
            // Rechte-Entzug ist ein zweiter, unabhängiger Mechanismus, der genau dort greifen
            // kann, wo Einfrieren es nicht tut.
            val revoked = runCatching { permissionRevoker.revokeDangerousPermissions(finding.packageName) }
                .getOrDefault(emptyList())
            if (revoked.isNotEmpty()) {
                logStore.append(
                    Log.WARN,
                    TAG,
                    "Gefährliche Rechte automatisch entzogen: pkg=${finding.packageName} rechte=$revoked",
                )
                // Merken, was entzogen wurde — sonst kann [trust] es später nicht wiederherstellen
                // (2026-08-29, Lückenschluss Feature 3, s. RevokedPermissionStore-Klassendoc).
                RevokedPermissionStore.record(context, finding.packageName, revoked)
            }
        }
    }

    fun trust(packageName: String) {
        store.trust(packageName)
        appManagementController.setFrozen(packageName, false)
        notifier.cancel(packageName)
        notifiedStore.clear(packageName)
        // Gegenstück zum Entzug in [enforce]: ein Fehlalarm soll gefährliche Rechte nicht
        // dauerhaft auf DENIED zurücklassen, nur weil der Fund inzwischen als vertrauenswürdig
        // markiert wurde (2026-08-29, Lückenschluss Feature 3 "Permission Auto-Block").
        val toRestore = RevokedPermissionStore.consume(context, packageName)
        if (toRestore.isNotEmpty()) {
            val restored = runCatching { permissionRevoker.restoreDefaultGrantState(packageName, toRestore) }
                .getOrDefault(emptyList())
            if (restored.isNotEmpty()) {
                logStore.append(
                    Log.WARN,
                    TAG,
                    "Automatisch entzogene Rechte wiederhergestellt: pkg=$packageName rechte=$restored",
                )
            }
        }
        logStore.append(Log.WARN, TAG, "Als vertrauenswürdig markiert (Verdachtsscanner): pkg=$packageName")
    }

    /**
     * Manuelles Gegenstück zum automatischen Pfad in [enforce] (2026-08-29, Feature 3 "Permission
     * Auto-Block" — der im Plan vorgesehene manuelle Baustein neben der bereits bestehenden
     * automatischen Durchsetzung). Vom Permission-Audit-Bildschirm aus pro App aufrufbar, unabhängig
     * von einem Verdachtsscanner-Fund — der Nutzer kann eine beliebige Fremd-App als "zu
     * freizügig" einstufen, ohne dass sie erst als Verdachtsfund auffallen muss. Nutzt denselben
     * [RevokedPermissionStore], damit ein späteres [trust] (falls die App auch als Verdachtsfund
     * auftaucht) dieselben Rechte wiederherstellen kann wie bei einem automatischen Entzug.
     */
    fun manuallyRevokeDangerousPermissions(packageName: String): List<String> {
        val revoked = runCatching { permissionRevoker.revokeDangerousPermissions(packageName) }
            .getOrDefault(emptyList())
        if (revoked.isNotEmpty()) {
            RevokedPermissionStore.record(context, packageName, revoked)
            logStore.append(Log.WARN, TAG, "Gefährliche Rechte manuell entzogen: pkg=$packageName rechte=$revoked")
        }
        return revoked
    }

    /** Gegenstück zu [manuallyRevokeDangerousPermissions] — stellt genau die über
     * [RevokedPermissionStore] gemerkten Rechte wieder her, nicht alle aktuell deklarierten
     * gefährlichen Rechte der App (die könnten inzwischen andere sein). */
    fun manuallyRestoreDangerousPermissions(packageName: String): List<String> {
        val toRestore = RevokedPermissionStore.consume(context, packageName)
        if (toRestore.isEmpty()) return emptyList()
        val restored = runCatching { permissionRevoker.restoreDefaultGrantState(packageName, toRestore) }
            .getOrDefault(emptyList())
        if (restored.isNotEmpty()) {
            logStore.append(Log.WARN, TAG, "Gefährliche Rechte manuell wiederhergestellt: pkg=$packageName rechte=$restored")
        }
        return restored
    }

    /** Für die Anzeige im Permission-Audit-Bildschirm: ob für dieses Paket aktuell manuell/
     * automatisch entzogene Rechte gemerkt sind, ohne sie zu konsumieren. */
    fun hasRevokedPermissions(packageName: String): Boolean =
        RevokedPermissionStore.peek(context, packageName).isNotEmpty()

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
