package de.ble1st.warden.bus

import android.content.Context
import android.content.Intent
import android.util.Log
import de.ble1st.warden.admin.DeviceOwnerStatusReader
import de.ble1st.warden.appmanagement.AppManagementController
import de.ble1st.warden.appmanagement.AppManagementInfo
import de.ble1st.warden.appmanagement.SuspiciousAppFindingInfo
import de.ble1st.warden.appmanagement.SuspiciousAppScanController
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
import de.ble1st.warden.integrity.RootIndicatorScanner
import de.ble1st.warden.integrity.StorageEncryptionStatusReader
import de.ble1st.warden.logging.HashChainLogStore
import de.ble1st.warden.registry.DeviceLockNowManager
import de.ble1st.warden.registry.DeviceLockdownBundle
import de.ble1st.warden.registry.PersistentSafeguardRegistry
import de.ble1st.warden.registry.RegistryStorage
import de.ble1st.warden.registry.SafeguardCatalog
import de.ble1st.warden.registry.SafeguardRegistryStore
import de.ble1st.warden.registry.WardenProfileApplier
import de.ble1st.warden.registry.WardenProfileApplyResult
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

    fun isSafeguardActive(safeguardId: String): Boolean =
        authorize(BusCommand.READ, "isSafeguardActive") { registry.isActive(safeguardId) }

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
     * Applies a named hardening set in **one** authorized call: IDs in the profile are applied,
     * every other reversible catalog ID is reverted. Lockdown stays presence-gated (not in this
     * registry). Failures are isolated per ID so one DPM rejection does not abort the rest.
     * [WardenProfileApplyResult.skipped] surfaces deliberate skips (e.g. FRP without stored
     * accounts) separately from [WardenProfileApplyResult.failed] so the caller (UI) can tell a
     * profile that applied cleanly apart from one that is silently incomplete.
     */
    fun applyProfile(profile: WardenProfile): WardenProfileApplyResult =
        authorize(BusCommand.NON_DESTRUCTIVE_SWITCH, "applyProfile") {
            val result = WardenProfileApplier(context, registry) { enabled ->
                UsbAutoLockStorage.setEnabled(context, enabled)
            }.apply(profile)
            UsbLockStateReceiver.syncRegistration(context)
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
     */
    private fun <T> authorize(command: BusCommand, methodName: String, action: () -> T): T {
        val allowed = CapabilityMatrix.isAllowed(Role.OWNER, command) &&
            rateLimiter.allow("${Role.OWNER}:$command")

        log(command, methodName, allowed)

        if (!allowed) {
            throw SecurityException("Concord-Bus: Aufruf abgelehnt ($methodName)")
        }
        return action()
    }

    /** Audit-Identität — reduziert gegenüber dem Quellprojekt (kein `uid`/`pid`/`lineage`-Knoten
     * mehr, s. Klassendoc): [Role.OWNER] ist der einzig mögliche Aufrufer, `presence` bleibt bis
     * zur Portierung der Presence-Schritte immer `n/a`. */
    private fun log(command: BusCommand, methodName: String, allowed: Boolean) {
        val message = "role=${Role.OWNER} cmd=$methodName class=$command presence=n/a allowed=$allowed"
        logStore.append(priority = if (allowed) Log.INFO else Log.WARN, tag = TAG, message = message)
    }

    private companion object {
        const val TAG = "ConcordBus"
        // Opening Safeguards reads every catalog ID; applying a profile remounts them in the
        // same window. 30 was enough for single toggles, not for a catalog remount.
        const val RATE_LIMIT_CALLS = 80
        const val RATE_LIMIT_WINDOW_MILLIS = 10_000L
    }
}
