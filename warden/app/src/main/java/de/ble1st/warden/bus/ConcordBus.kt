package de.ble1st.warden.bus

import android.content.Context
import android.content.Intent
import android.util.Log
import de.ble1st.warden.admin.DeviceOwnerStatusReader
import de.ble1st.warden.appmanagement.AppManagementController
import de.ble1st.warden.appmanagement.AppManagementInfo
import de.ble1st.warden.appmanagement.SuspiciousAppFindingInfo
import de.ble1st.warden.appmanagement.SuspiciousAppScanController
import de.ble1st.warden.clipboard.ClipboardAccessController
import de.ble1st.warden.clipboard.ClipboardGuardController
import de.ble1st.warden.clipboard.ClipboardGuardStorage
import de.ble1st.warden.domain.clipboard.ClipboardAccessEvent
import de.ble1st.warden.domain.bus.BusCommand
import de.ble1st.warden.presence.LogViewerActivity
import de.ble1st.warden.domain.bus.CapabilityMatrix
import de.ble1st.warden.domain.bus.RateLimiter
import de.ble1st.warden.domain.bus.Role
import de.ble1st.warden.domain.profile.WardenProfile
import de.ble1st.warden.domain.registry.SafeguardRegistry
import de.ble1st.warden.integrity.DebuggableOsStatusReader
import de.ble1st.warden.integrity.DeveloperOptionsStatusReader
import de.ble1st.warden.integrity.DeviceIntegrityStatus
import de.ble1st.warden.integrity.AdvancedProtectionReader
import de.ble1st.warden.integrity.KeyAttestationReader
import de.ble1st.warden.integrity.KeystoreSecurityLevelReader
import de.ble1st.warden.integrity.RootIndicatorScanner
import de.ble1st.warden.integrity.StorageEncryptionStatusReader
import de.ble1st.warden.logging.HashChainLogStore
import de.ble1st.warden.profile.AutoProfileStorage
import de.ble1st.warden.registry.DeviceLockNowManager
import de.ble1st.warden.registry.DeviceLockdownBundle
import de.ble1st.warden.registry.PersistentSafeguardRegistry
import de.ble1st.warden.registry.RegistryStorage
import de.ble1st.warden.registry.SafeguardCatalog
import de.ble1st.warden.registry.SafeguardRegistryStore
import de.ble1st.warden.registry.WardenProfileApplier
import de.ble1st.warden.registry.WardenProfileApplyResult
import de.ble1st.warden.antitheft.AntiTheftAlarmStorage
import de.ble1st.warden.antitheft.AntiTheftLockStateReceiver
import de.ble1st.warden.tracker.TrackerGuardStorage
import de.ble1st.warden.usb.UsbAutoLockStorage
import de.ble1st.warden.usb.UsbLockStateReceiver
import de.ble1st.warden.wardenAuditLog

/**
 * Meilenstein E.1 (Konzept Abschnitt 2/2b/19), **in-process statt AIDL-Service** — s.
 * Plan-Abschnitt "Concord-Bus: In-Process statt AIDL, `exposed=false`". Im ConneXias-Framework-
 * Quellprojekt war dies ein gebundener, exportierter `Service` (`ConcordBusService`), den
 * Herald/Sentinel/Barbican als fremde UIDs über AIDL ansprachen. Hier gibt es nur noch Wardens
 * eigene UI als Aufrufer — deshalb eine normale Kotlin-Klasse, kein `Service`/`Binder`/AIDL mehr,
 * und die Autorisierungs-Pipeline verliert ihre erste Schicht ([CallerVerifier][
 * de.ble1st.warden.domain.bus.CallerVerificationResult], UID/Paket/Zertifikat-Lineage-Prüfung —
 * es gibt keine fremde Identität mehr zu verifizieren). Jede Methode läuft weiterhin durch
 * [authorize]: Autorisierung ([CapabilityMatrix], jetzt gegen das einzige [Role.OWNER]) →
 * Rate-Limiting ([RateLimiter], schützt weiterhin gegen versehentliche UI-Schleifen) →
 * Audit-Log ([HashChainLogStore]) — in dieser Reihenfolge, jeder Schritt kann den Aufruf beenden,
 * bevor der nächste überhaupt läuft.
 *
 * `exposed=false`: strukturell erfüllt, weil es schlicht keinen `<service>`/IPC-Endpunkt mehr
 * gibt (kein Manifest-Eintrag nötig). Ein späterer, cross-APK-fähiger `exported`-Service ließe
 * sich um genau diese Klasse wickeln (Autorisierungs-Pipeline unverändert wiederverwendbar),
 * falls das Projekt das je wieder braucht.
 *
 * **Eigene, unabhängige Registry-Instanz** (wie im Quellprojekt): dieselbe Envelope-Datei
 * (`RegistryStorage.buildEnvelopeFile`) wie `RegistryReconciliationReceiver`/`FailsafeActivity`,
 * aber kein geteiltes In-Memory-Objekt — die eigentliche Quelle der Wahrheit ist ohnehin der
 * DPM-Ist-Zustand (`isActive()` fragt live ab) bzw. die Datei für den Soll-Zustand, kein
 * In-Memory-Zustand, der zwischen mehreren Instanzen divergieren könnte.
 */
class ConcordBus(
    private val context: Context,
    private val appManagementController: AppManagementController,
    private val suspiciousAppScanController: SuspiciousAppScanController,
) {

    private val deviceOwnerStatusReader = DeviceOwnerStatusReader(context)
    private val debuggableOsStatusReader = DebuggableOsStatusReader()
    private val rootIndicatorScanner = RootIndicatorScanner(context)
    private val developerOptionsStatusReader = DeveloperOptionsStatusReader(context)
    private val storageEncryptionStatusReader = StorageEncryptionStatusReader(context)
    private val keystoreSecurityLevelReader = KeystoreSecurityLevelReader(context)

    // 2026-09-05, Tier-1 der DPC-Recherche: der kryptografische Gegenpart zu rootIndicatorScanners
    // Heuristik (Key Attestation) und Androids eigener „Erweiterter Schutz" (AAPM, ab Android 16 —
    // auf älteren Systemen liefert der Reader NICHT_VERFUEGBAR statt eines geratenen „aus").
    private val keyAttestationReader = KeyAttestationReader()
    private val advancedProtectionReader = AdvancedProtectionReader(context)
    private val deviceLockNowManager = DeviceLockNowManager(context)
    // "Arbeite langsam am Lockdownmodus" (2026-08-22), dritter Schritt: rein lesende Instanz fürs
    // Dashboard-Statuslicht — bewusst NICHT in `registry` unten registriert (dort absichtlich
    // ausgespart, s. dortiger Kommentar): Scharf-/Zurückschalten bleibt exklusiv presence-gated
    // über SensitiveAction.LOCKDOWN_MODE_ARM/MASTER_SWITCH_REVERT (SensitiveActionActivity), kein
    // applySafeguard/revertSafeguard-Zweitweg über diesen Bus.
    private val deviceLockdownBundle by lazy { DeviceLockdownBundle.build(context) }
    private val rateLimiter = RateLimiter(maxCallsPerWindow = RATE_LIMIT_CALLS, windowMillis = RATE_LIMIT_WINDOW_MILLIS)
    private val logStore by lazy { wardenAuditLog(context) }

    private val registry by lazy {
        PersistentSafeguardRegistry(
            SafeguardRegistry(),
            SafeguardRegistryStore(RegistryStorage.buildEnvelopeFile(context)),
        ).apply {
            // Reversible catalog only — lockdown stays presence-gated (SensitiveAction / Failsafe).
            SafeguardCatalog.registerReversible(this, context)
            load()
        }
    }

    fun isDeviceOwner(): Boolean =
        authorize(BusCommand.READ, "isDeviceOwner") { deviceOwnerStatusReader.isDeviceOwner() }

    fun isDebuggableOs(): Boolean =
        authorize(BusCommand.READ, "isDebuggableOs") { debuggableOsStatusReader.isDebuggableOs() }

    /** Feature 8/9 ("weitere Funktionen für den Sicherheitsscanner", 2026-08-22): Root-/Magisk-/
     * Custom-ROM-Indikatoren + ADB-/Entwickleroptionen-Status, gebündelt für den
     * "Geräte-Integrität"-Abschnitt im Sicherheits-Scanner-Bildschirm. */
    fun deviceIntegrityStatus(): DeviceIntegrityStatus =
        authorize(BusCommand.READ, "deviceIntegrityStatus") {
            DeviceIntegrityStatus(
                rootIndicators = rootIndicatorScanner.scan(),
                adbEnabled = developerOptionsStatusReader.isAdbEnabled(),
                developerOptionsEnabled = developerOptionsStatusReader.isDeveloperOptionsEnabled(),
                storageEncrypted = storageEncryptionStatusReader.isEncrypted(),
                keystoreSecurityLevel = keystoreSecurityLevelReader.read(),
                attestation = keyAttestationReader.read(),
                advancedProtection = advancedProtectionReader.read(),
            )
        }

    /** "LOCK_NOW als Device Command" (2026-08-22) — niedrigschwelliger Dashboard-Befehl, s.
     * [DeviceLockNowManager]-Klassendoc für die Begründung, warum hier bewusst kein Presence-Gate
     * nötig ist wie beim gleichnamigen `SensitiveAction.LOCK_NOW`. `NON_DESTRUCTIVE_SWITCH` statt
     * `READ` — mutiert Gerätezustand, auch wenn reversibel/ungefährlich (dieselbe Einstufung wie
     * [applySafeguard]/[setAppFrozen]). */
    fun lockNow(): Boolean =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "lockNow") {
            deviceLockNowManager.lockNow()
            true
        }

    fun listSafeguards(): List<String> =
        authorize(BusCommand.READ, "listSafeguards") { registry.registeredIds().toList() }

    /** Rein lesend fürs Dashboard-Statuslicht (Safeguards-Screen) — s. [deviceLockdownBundle]-
     * Kommentar oben, warum das absichtlich kein `applySafeguard`/`revertSafeguard`-Pfad ist. */
    fun isLockdownModeActive(): Boolean =
        authorize(BusCommand.READ, "isLockdownModeActive") { deviceLockdownBundle.isActive() }

    /**
     * Ist-Zustand mehrerer Safeguards in **einem** autorisierten Aufruf (2026-08-28, aus der
     * Code-/Sicherheitsanalyse, Befund Q-2).
     *
     * Der Safeguards-Screen braucht den Zustand aller 33 Katalogeinträge auf einmal. Über die
     * frühere Einzelmethode `isSafeguardActive` waren das 33 Durchläufe durch [authorize] — also
     * 33 Rate-Limit-Token und 33 `HashChainLogStore.append`-Aufrufe, von denen jeder das gesamte
     * aktive Segment liest,
     * entschlüsselt, neu verschlüsselt und schreibt. Ein Bildschirmaufbau füllte damit rund ein
     * Fünfzehntel eines 500er-Log-Segments und kam dem Rate-Limit von
     * [RATE_LIMIT_CALLS]/[RATE_LIMIT_WINDOW_MILLIS] gefährlich nahe; wurde es überschritten, warf
     * [authorize] und die UI meldete irreführend "vermutlich kein Device Owner aktiv".
     *
     * Die eigentlichen DPM-Abfragen bleiben unverändert einzeln und live (`isActive()` cached
     * nichts, s. `Safeguard`-Doc) — gebündelt wird nur die Autorisierung darum herum.
     *
     * **Fehler bleiben pro Eintrag isoliert:** der Wert ist `null`, wenn genau dieser
     * `isActive()`-Aufruf geworfen hat (z. B. eine DPM-Restriction, die das Gerät nicht kennt) —
     * dieselbe "`null` heißt: nicht lesbar, nicht: aus"-Unterscheidung wie bei den Einzel-Lesern
     * in der UI. Ein einzelner nicht unterstützter Safeguard darf nicht den ganzen Bildschirm
     * blind machen. Eine abgelehnte *Autorisierung* wirft dagegen weiterhin für den ganzen Aufruf
     * — dann ist tatsächlich kein Wert vertrauenswürdig.
     */
    fun safeguardStates(safeguardIds: Collection<String>): Map<String, Boolean?> =
        authorize(BusCommand.READ, "safeguardStates") {
            safeguardIds.associateWith { id ->
                runCatching { registry.isActive(id) }
                    .onFailure { Log.w(TAG, "Safeguard-Zustand ($id) nicht lesbar", it) }
                    .getOrNull()
            }
        }

    fun applySafeguard(safeguardId: String): Boolean =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "applySafeguard") {
            registry.apply(safeguardId)
            true
        }

    fun revertSafeguard(safeguardId: String): Boolean =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "revertSafeguard") {
            registry.revert(safeguardId)
            true
        }

    /** Meilenstein "Barbican als eigener Prozess" (2026-08-31, `docs/design-barbican-prozess-
     * childvpn.md`) — einziger [Role.BARBICAN]-Aufruf, erreicht über [ConcordBusService] aus dem
     * `:barbican`-Prozess. Kein `SecurityException`-Catch hier (anders als beim AIDL-Client): eine
     * Ablehnung (Rate-Limit) soll den Aufrufer erreichen, dieselbe "eine verschluckte Ablehnung
     * wäre so gefährlich wie eine fälschlich akzeptierte"-Haltung wie bei jedem anderen
     * [authorize]-Aufruf hier. */
    fun reportBarbicanEvent(priority: Int, message: String): Boolean =
        authorize(BusCommand.EVENT_REPORT, "reportBarbicanEvent", Role.BARBICAN) {
            logStore.append(priority = priority, tag = "Barbican", message = message)
            true
        }

    /** Autorisiert nur, ob [LogViewerActivity] startet; der eigentliche Presence-Nachweis läuft
     * dort, nicht hier — s. dortiges Klassendoc. */
    fun requestLogAccess(): Boolean =
        authorize(BusCommand.LOG_ACCESS, "requestLogAccess") {
            context.startActivity(
                Intent(context, LogViewerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }

    fun listManagedApps(): List<AppManagementInfo> =
        authorize(BusCommand.READ, "listManagedApps") { appManagementController.listApps() }

    fun isAppFrozen(targetPackage: String): Boolean =
        authorize(BusCommand.READ, "isAppFrozen") { appManagementController.isFrozen(targetPackage) }

    fun setAppFrozen(targetPackage: String, frozen: Boolean): Boolean =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "setAppFrozen") {
            appManagementController.setFrozen(targetPackage, frozen)
        }

    fun isAutoFreezeScannerEnabled(): Boolean =
        authorize(BusCommand.READ, "isAutoFreezeScannerEnabled") { suspiciousAppScanController.isEnabled() }

    fun setAutoFreezeScannerEnabled(enabled: Boolean): Boolean =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "setAutoFreezeScannerEnabled") {
            suspiciousAppScanController.setEnabled(enabled)
            true
        }

    fun listSuspiciousAppFindings(): List<SuspiciousAppFindingInfo> =
        authorize(BusCommand.READ, "listSuspiciousAppFindings") { suspiciousAppScanController.scanWithDetails() }

    fun trustSuspiciousApp(targetPackage: String): Boolean =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "trustSuspiciousApp") {
            suspiciousAppScanController.trust(targetPackage)
            true
        }

    /** Feature 3 ("Permission Auto-Block", 2026-08-29) — manueller Baustein neben der bereits
     * bestehenden automatischen Durchsetzung in [SuspiciousAppScanController.enforce]: der Nutzer
     * kann einer beliebigen Fremd-App ihre gefährlichen Rechte entziehen, ohne auf einen
     * Verdachtsfund zu warten. S. [de.ble1st.warden.ui.PermissionAuditScreen]. */
    fun revokeDangerousPermissions(targetPackage: String): List<String> =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "revokeDangerousPermissions") {
            suspiciousAppScanController.manuallyRevokeDangerousPermissions(targetPackage)
        }

    fun restoreDangerousPermissions(targetPackage: String): List<String> =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "restoreDangerousPermissions") {
            suspiciousAppScanController.manuallyRestoreDangerousPermissions(targetPackage)
        }

    fun hasRevokedPermissions(targetPackage: String): Boolean =
        authorize(BusCommand.READ, "hasRevokedPermissions") {
            suspiciousAppScanController.hasRevokedPermissions(targetPackage)
        }

    /** Feature 10 ("manuelle Sofort-Scan-Auslösung", 2026-08-22) — löst einen vollständigen
     * Scan-Lauf sofort aus, statt auf den nächsten periodischen WorkManager-Slot zu warten. */
    fun runImmediateSuspiciousAppScan(): List<SuspiciousAppFindingInfo> =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "runImmediateSuspiciousAppScan") {
            suspiciousAppScanController.runImmediateScan()
        }

    /** "USB automatisch sperren bei Bildschirmsperre" (2026-08-22, GrapheneOS-Vorbild) — reine
     * lokale Präferenz ([UsbAutoLockStorage]), kein `Safeguard`/registry-Eintrag: der eigentliche
     * DPM-Zustand wird periodisch von [de.ble1st.warden.usb.UsbAutoLockController] anhand des
     * Sperrzustands nachgeführt, nicht hier direkt umgeschaltet — dieselbe Trennung wie beim
     * Scanner-Ein/Aus ([isAutoFreezeScannerEnabled]/[setAutoFreezeScannerEnabled]) oben. */
    fun isUsbAutoLockEnabled(): Boolean =
        authorize(BusCommand.READ, "isUsbAutoLockEnabled") { UsbAutoLockStorage.isEnabled(context) }

    fun setUsbAutoLockEnabled(enabled: Boolean): Boolean =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "setUsbAutoLockEnabled") {
            UsbAutoLockStorage.setEnabled(context, enabled)
            UsbLockStateReceiver.syncRegistration(context)
            true
        }

    /**
     * "Zwischenablage-Wächter" (`docs/design-clipboard-guard.md`, Phase 1) — genau wie
     * [isUsbAutoLockEnabled]/[setUsbAutoLockEnabled] eine reine lokale Präferenz
     * ([ClipboardGuardStorage]), kein `Safeguard`/registry-Eintrag: Android hat keine
     * `DevicePolicyManager`-API für die Zwischenablage (s. Konzept Abschnitt 1), also gibt es
     * keinen Ist-Zustand gegen die Plattform abzugleichen. Der tatsächliche Abgleich läuft in
     * [de.ble1st.warden.clipboard.ClipboardGuardController.checkAndClearIfStale], aufgerufen von
     * `WardenStatusActivity.onWindowFocusChanged` — nur dort ist Fensterfokus (und damit
     * funktionierender Zwischenablage-Zugriff) tatsächlich garantiert, s. Konzept Abschnitt 2.1.
     */
    fun isClipboardGuardEnabled(): Boolean =
        authorize(BusCommand.READ, "isClipboardGuardEnabled") { ClipboardGuardStorage.isEnabled(context) }

    fun setClipboardGuardEnabled(enabled: Boolean): Boolean =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "setClipboardGuardEnabled") {
            ClipboardGuardStorage.setEnabled(context, enabled)
            true
        }

    /** "Diebstahlschutz-Alarm" (2026-09-03, Ideenliste Punkte 3+4) — zwei unabhängige lokale
     * Präferenzen ([AntiTheftAlarmStorage]), kein `Safeguard`/registry-Eintrag, dasselbe Muster wie
     * [isUsbAutoLockEnabled]/[setUsbAutoLockEnabled] oben: [de.ble1st.warden.antitheft
     * .AntiTheftLockStateReceiver] führt den eigentlichen Zustand nach, hier wird nur die
     * Präferenz geschrieben und die Empfänger-Registrierung synchron gehalten. */
    fun isAntiTheftMotionAlarmEnabled(): Boolean =
        authorize(BusCommand.READ, "isAntiTheftMotionAlarmEnabled") { AntiTheftAlarmStorage.load(context).motionAlarmEnabled }

    fun setAntiTheftMotionAlarmEnabled(enabled: Boolean): Boolean =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "setAntiTheftMotionAlarmEnabled") {
            AntiTheftAlarmStorage.setMotionAlarmEnabled(context, enabled)
            AntiTheftLockStateReceiver.syncRegistration(context)
            true
        }

    fun isAntiTheftChargerAlarmEnabled(): Boolean =
        authorize(BusCommand.READ, "isAntiTheftChargerAlarmEnabled") { AntiTheftAlarmStorage.load(context).chargerAlarmEnabled }

    fun setAntiTheftChargerAlarmEnabled(enabled: Boolean): Boolean =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "setAntiTheftChargerAlarmEnabled") {
            AntiTheftAlarmStorage.setChargerAlarmEnabled(context, enabled)
            AntiTheftLockStateReceiver.syncRegistration(context)
            true
        }

    /** "BLE-Tracker-Wächter" (2026-09-03, Ideenliste "auch AirTags") — reine lokale Präferenz
     * ([TrackerGuardStorage]), kein `Safeguard`/registry-Eintrag: [de.ble1st.warden.tracker
     * .BleTrackerWorker] ist bereits unbedingt geplant (dasselbe "immer geplant, Controller prüft
     * selbst"-Muster wie bei Cell-Security/WiFi-Vertrauensliste), dieser Schalter steuert nur, ob
     * [de.ble1st.warden.tracker.BleTrackerController.checkOnce] etwas tut. */
    fun isTrackerGuardEnabled(): Boolean =
        authorize(BusCommand.READ, "isTrackerGuardEnabled") { TrackerGuardStorage.isEnabled(context) }

    fun setTrackerGuardEnabled(enabled: Boolean): Boolean =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "setTrackerGuardEnabled") {
            TrackerGuardStorage.setEnabled(context, enabled)
            true
        }

    /** Letzter Leer-Zeitpunkt für die Dashboard-Anzeige, `null` = noch nie geleert. Reines Lesen,
     * keine Nebenwirkung — [BusCommand.READ]. */
    fun clipboardLastClearedAt(): Long? =
        authorize(BusCommand.READ, "clipboardLastClearedAt") { ClipboardGuardStorage.lastClearAt(context) }

    /**
     * Ungegateter manueller Pfad (Dashboard-Button "Zwischenablage jetzt leeren"), analog
     * [lockNow]: reversibel/harmlos, der Nutzer löst es selbst auf einem bereits entsperrten
     * Gerät aus, kein Presence-Nachweis nötig. Leert unabhängig vom Auto-Clear-Toggle — anders
     * als [de.ble1st.warden.clipboard.ClipboardGuardController.checkAndClearIfStale] respektiert
     * dieser Pfad weder [isClipboardGuardEnabled] noch die Alters-Schwelle. Gibt zurück, ob
     * tatsächlich Inhalt vorhanden war (reine Anzeige-Info, kein Fehlerzustand bei `false`).
     */
    fun clearClipboardNow(): Boolean =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "clearClipboardNow") {
            ClipboardGuardController(context).clearNow()
        }

    /**
     * Phase 2 (`docs/design-clipboard-guard.md` Abschnitt 3.2.7) — Wardens App-seitige Präferenz
     * für den Cross-App-`AccessibilityService`, unabhängig von der tatsächlichen Systemfreigabe
     * (s. `de.ble1st.warden.clipboard.ClipboardAccessibilityStatus`, in der UI getrennt angezeigt).
     * Bewusst [BusCommand.NON_DESTRUCTIVE_SWITCH] statt einer eigenen Kategorie — dieselbe
     * Semantik wie [setClipboardGuardEnabled], entsprechend immer im Audit-Log sichtbar (anders
     * als ein reiner [BusCommand.READ]), was hier besonders passend ist: das Ein-/Ausschalten
     * dieser konkreten Fähigkeit gehört selbst zu den Vorgängen, die nachvollziehbar bleiben
     * sollen.
     */
    fun isClipboardCrossAppMonitoringEnabled(): Boolean =
        authorize(BusCommand.READ, "isClipboardCrossAppMonitoringEnabled") {
            ClipboardGuardStorage.isCrossAppMonitoringEnabled(context)
        }

    fun setClipboardCrossAppMonitoringEnabled(enabled: Boolean): Boolean =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "setClipboardCrossAppMonitoringEnabled") {
            ClipboardGuardStorage.setCrossAppMonitoringEnabled(context, enabled)
            true
        }

    /** Beobachtete Cross-App-Zugriffe für die Dashboard-Liste, neueste zuerst — reines Lesen,
     * [BusCommand.READ]. */
    fun recentClipboardAccessEvents(limit: Int = 100): List<ClipboardAccessEvent> =
        authorize(BusCommand.READ, "recentClipboardAccessEvents") {
            ClipboardAccessController(context).recentEvents(limit).asReversed()
        }

    /** "Verlauf löschen" im ClipboardGuard-Screen — löscht nur die dedizierte Ereignisliste, nicht
     * den `HashChainLogStore`-Metadaten-Eintrag ("Zwischenablage-naher Zugriff erkannt: …"), der
     * absichtlich manipulationssicher bleibt (s. `ClipboardAccessController`-Klassendoc). */
    fun clearClipboardAccessHistory(): Boolean =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "clearClipboardAccessHistory") {
            ClipboardAccessController(context).clearHistory()
            true
        }

    /**
     * Applies a named hardening set in **one** authorized call: IDs in the profile are applied,
     * every other reversible catalog ID is reverted. Lockdown stays presence-gated (not in this
     * registry). Failures are isolated per ID so one DPM rejection does not abort the rest.
     * [WardenProfileApplyResult.skipped] surfaces deliberate skips (e.g. FRP without stored
     * accounts) separately from [WardenProfileApplyResult.failed] so the caller (UI) can tell a
     * profile that applied cleanly apart from one that is silently incomplete.
     */
    fun applyProfile(profile: WardenProfile): WardenProfileApplyResult =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "applyProfile") {
            val result = WardenProfileApplier(
                context = context,
                registry = registry,
                setUsbAutoLock = { enabled -> UsbAutoLockStorage.setEnabled(context, enabled) },
                // analyse.md (2. Durchgang, Hoch): reale Bündel-Instanz statt der `registry` oben
                // (die registriert nur den reversiblen Katalog, s. deren eigener Kommentar) — nur
                // so kann der Applier erkennen, ob das Lockdown-Bündel gerade presence-armed ist.
                isLockdownActive = { deviceLockdownBundle.isActive() },
            ).apply(profile)
            // Wirkendes Profil hier festhalten, nicht bei den Aufrufern (2026-08-28, Befund Q-1):
            // sowohl der manuelle Tap als auch AutoProfileController laufen zwingend durch diese
            // Methode, also ist das die einzige Stelle, an der der Stand nicht auseinanderlaufen
            // kann. AutoProfileDecision braucht ihn, um eine manuelle Verschärfung nicht beim
            // nächsten Zeitplanlauf stillschweigend herunterzuschalten.
            AutoProfileStorage.saveLastEffective(context, profile)
            UsbLockStateReceiver.syncRegistration(context)
            // Status-Widget (widget/WardenStatusWidget.kt, 2026-09-03): einziger Aufrufpunkt, den
            // sowohl der manuelle Profil-Tap als auch AutoProfileController zwingend durchlaufen —
            // s. dortiges Klassendoc, warum genau hier statt in DestructiveActionExecutor.
            de.ble1st.warden.widget.WardenStatusWidget.requestUpdate(context)
            if (result.failed.isNotEmpty()) {
                Log.w(TAG, "Profil ${profile.name}: fehlgeschlagen für ${result.failed}")
            }
            if (result.skipped.isNotEmpty()) {
                Log.w(TAG, "Profil ${profile.name}: übersprungen für ${result.skipped}")
            }
            result
        }

    /**
     * Zentrale Autorisierungs-Pipeline (s. Klassendoc). Wirft [SecurityException] statt `false`
     * o. Ä. zurückzugeben — ein abgelehnter Bus-Aufruf muss für den Aufrufer eindeutig als Fehler
     * ankommen, nicht als valider, aber "leerer"/negativer Wert (Fail-Safe, Invariante 6, gilt
     * auch hier: eine verschluckte Ablehnung wäre so gefährlich wie eine fälschlich akzeptierte).
     *
     * [role] default `Role.OWNER` — bis 2026-08-31 der einzig mögliche Aufrufer, deshalb blieben
     * alle bestehenden Aufrufstellen unverändert. [reportBarbicanEvent] ist die erste Ausnahme
     * ([Role.BARBICAN], s. dortiger Kommentar).
     */
    private fun <T> authorize(command: BusCommand, methodName: String, role: Role = Role.OWNER, action: () -> T): T {
        val allowed = CapabilityMatrix.isAllowed(role, command) &&
            rateLimiter.allow("$role:$command")

        log(command, methodName, role, allowed)

        if (!allowed) {
            throw SecurityException("Concord-Bus: Aufruf abgelehnt ($methodName)")
        }
        return action()
    }

    /**
     * Audit-Identität — reduziert gegenüber dem Quellprojekt (kein `uid`/`pid`/`lineage`-Knoten
     * mehr, s. Klassendoc): [role] ist [Role.OWNER] oder [Role.BARBICAN] (die einzigen zwei
     * möglichen Aufrufer, s. [Role]-Klassendoc), `presence` bleibt bis zur Portierung der
     * Presence-Schritte immer `n/a`.
     *
     * **Erfolgreiche [BusCommand.READ]-Aufrufe werden seit 2026-08-28 nicht mehr protokolliert**
     * (Befund Q-2). Ein Audit-Log soll Entscheidungen und Zustandsänderungen festhalten, nicht das
     * Rendern einer Liste: die Lese-Einträge waren die mit Abstand häufigsten, sagten inhaltlich
     * nichts aus ("die UI hat einen Status angezeigt") und verdrängten durch die Segmentrotation
     * genau die Einträge, für die dieses Log existiert. Jeder [append][HashChainLogStore.append]
     * schreibt außerdem das ganze aktive Segment neu — ein Bildschirmaufbau kostete so dutzende
     * vollständige Datei-Schreibzyklen.
     *
     * **Ein *abgelehnter* Read wird weiterhin protokolliert**, und das ist der sicherheitsrelevante
     * Teil: er heißt, dass die Capability-Matrix oder das Rate-Limit gegriffen hat — ein Ereignis,
     * das ohne Eintrag nirgends sichtbar wäre. Alle anderen Kommandoklassen (schaltend,
     * Log-Zugriff, Ereignis-Meldung) bleiben unverändert immer im Log.
     */
    private fun log(command: BusCommand, methodName: String, role: Role, allowed: Boolean) {
        if (allowed && command == BusCommand.READ) return
        val message = "role=$role cmd=$methodName class=$command presence=n/a allowed=$allowed"
        logStore.append(priority = if (allowed) Log.INFO else Log.WARN, tag = TAG, message = message)
    }

    private companion object {
        const val TAG = "ConcordBus"
        // Opening Safeguards reads every catalog ID; applying a profile remounts them in the
        // same window. 30 was enough for single toggles, not for a catalog remount.
        // Seit safeguardStates() (2026-08-28, Befund Q-2) kostet ein Bildschirmaufbau statt 34
        // nur noch wenige Token — die Grenze bleibt trotzdem stehen: sie schützt gegen
        // versehentliche UI-Schleifen, und dafür ist reichlich Luft besser als knapp bemessen.
        const val RATE_LIMIT_CALLS = 80
        const val RATE_LIMIT_WINDOW_MILLIS = 10_000L
    }
}
