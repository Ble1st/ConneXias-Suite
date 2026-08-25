package de.ble1st.warden

import android.Manifest
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import de.ble1st.warden.logging.HashChainLogStore
import de.ble1st.warden.logging.LogStorage
import de.ble1st.warden.admin.WardenDeviceAdminReceiver
import de.ble1st.warden.autoreboot.AutoRebootWorker
import de.ble1st.warden.appmanagement.AccessibilityServiceScanner
import de.ble1st.warden.appmanagement.ActivationHistoryStore
import de.ble1st.warden.appmanagement.ActiveCapabilityReader
import de.ble1st.warden.appmanagement.AppDataWiper
import de.ble1st.warden.appmanagement.AppFreezeManager
import de.ble1st.warden.appmanagement.AppManagementController
import de.ble1st.warden.appmanagement.AppUninstaller
import de.ble1st.warden.appmanagement.DangerousPermissionRevoker
import de.ble1st.warden.appmanagement.DeviceAdminCapabilityScanner
import de.ble1st.warden.appmanagement.InstallSourceScanner
import de.ble1st.warden.appmanagement.InstalledAppLister
import de.ble1st.warden.appmanagement.NotificationListenerScanner
import de.ble1st.warden.appmanagement.OverlayPermissionScanner
import de.ble1st.warden.appmanagement.PackageVersionReader
import de.ble1st.warden.appmanagement.SigningCertHistoryStore
import de.ble1st.warden.appmanagement.SigningCertReader
import de.ble1st.warden.appmanagement.VersionHistoryStore
import de.ble1st.warden.appmanagement.SuspiciousAppNotifiedStore
import de.ble1st.warden.appmanagement.SuspiciousAppNotifier
import de.ble1st.warden.appmanagement.SuspiciousAppScanController
import de.ble1st.warden.appmanagement.SuspiciousAppScanStorage
import de.ble1st.warden.appmanagement.SuspiciousAppScanStore
import de.ble1st.warden.appmanagement.SuspiciousAppScanWorker
import de.ble1st.warden.bus.ConcordBus
import de.ble1st.warden.performance.BatterySamplingWorker
import de.ble1st.warden.presence.WardenLockSession
import de.ble1st.warden.usb.UsbAutoLockWorker
import de.ble1st.warden.usb.UsbLockStateReceiver
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * App-weite `Application`-Klasse. Stark gekürzte Fassung gegenüber dem ConneXias-Framework-
 * Quellprojekt: dort trug diese Klasse App-weite Instanzen mehrerer inzwischen entfallener
 * Cross-APK-Komponenten — `SuiteMembershipReconciliationWorker` (Mitgliedschafts-Abgleich
 * zwischen mehreren Geschwister-APKs, entfällt: nur noch eine APK), `SentinelWatchdogController`
 * (Cross-Process-Death-Watchdog, entfällt: Sentinel läuft jetzt in diesem Prozess, s. Plan-
 * Abschnitt "Presence: Sentinels PIN-Logik portiert"), `NetLockdownController`/
 * `FirewallPolicyController` (VPN/Barbican, entfällt vollständig), `TrustedCallerAllowlist`
 * (Cross-APK-Zertifikatsprüfung, entfällt: kein fremder Aufrufer mehr) und
 * `PackageUninstallProtectionSafeguard` für die (ebenfalls entfallenen) Geschwister-Suite-APKs.
 *
 * Wird in einem kommenden Schritt (Presence/PIN) um eine weitere `by lazy`-Instanz erweitert.
 */
class WardenApplication : Application() {

    /** Milestone "App-Verwaltung: Einfrieren/Deaktivieren": `by lazy` — keine Konstruktion,
     * bevor tatsächlich ein Aufruf sie braucht ([de.ble1st.warden.bus.ConcordBus]). */
    val appManagementController: AppManagementController by lazy {
        AppManagementController(
            context = this,
            appLister = InstalledAppLister(this),
            freezeManager = AppFreezeManager(this),
            ownPackageName = packageName,
            protectedPackageNames = AppManagementController.SUITE_PACKAGE_NAMES,
        )
    }

    /** Milestone "Automatisches Einfrieren verdächtiger Apps", seit "weitere Funktionen für den
     * Sicherheitsscanner" (2026-08-22) um sechs weitere Scanner/Stores erweitert — `by lazy` wie
     * [appManagementController] — auf dessen bereits geschütztem Freeze-Pfad aufgesetzt, kein
     * zweiter Mechanismus. */
    val suspiciousAppScanController: SuspiciousAppScanController by lazy {
        SuspiciousAppScanController(
            context = this,
            appLister = InstalledAppLister(this),
            adminReader = DeviceAdminCapabilityScanner(this),
            accessibilityScanner = AccessibilityServiceScanner(this),
            overlayScanner = OverlayPermissionScanner(this),
            notificationListenerScanner = NotificationListenerScanner(this),
            installSourceScanner = InstallSourceScanner(this),
            signingCertReader = SigningCertReader(this),
            signingCertHistoryStore = SigningCertHistoryStore(this),
            versionReader = PackageVersionReader(this),
            versionHistoryStore = VersionHistoryStore(this),
            activeCapabilityReader = ActiveCapabilityReader(this),
            activationHistoryStore = ActivationHistoryStore(this),
            notifiedStore = SuspiciousAppNotifiedStore(this),
            store = SuspiciousAppScanStore(SuspiciousAppScanStorage.buildEnvelopeFile(this)),
            appManagementController = appManagementController,
            notifier = SuspiciousAppNotifier(this),
            uninstaller = AppUninstaller(this),
            dataWiper = AppDataWiper(this),
            permissionRevoker = DangerousPermissionRevoker(this),
            ownPackageName = packageName,
            protectedPackageNames = AppManagementController.SUITE_PACKAGE_NAMES,
        )
    }

    /** App-weite Instanz — Wardens eigene UI ist der einzig verbleibende Aufrufer, s.
     * [ConcordBus]-Klassendoc. `by lazy`, dieselbe "nicht eager in `onCreate()`"-Vorsicht wie im
     * Quellprojekt: keine Konstruktion, bevor tatsächlich ein Aufruf sie braucht. */
    val concordBus: ConcordBus by lazy { ConcordBus(this, appManagementController, suspiciousAppScanController) }

    /** One in-process instance for the shared log envelope — callers must not construct their own
     * [HashChainLogStore] on the same file (append races lose entries and stay chain-valid).
     * Wipe-guard anchor passed (s. [HashChainLogStore]-Klassendoc "Wipe-Guard") — the app's real
     * audit trail is exactly the store a full-segment-deletion attack targets, unlike the
     * anchor-less instrumented-test instances. */
    val auditLogStore: HashChainLogStore by lazy {
        HashChainLogStore(LogStorage.buildEnvelopeFile(this), wipeGuardAnchorFile = LogStorage.buildWipeGuardAnchorFile(this))
    }

    /** WardenLock (Finalisierungsphase, 2026-08-24, auf Nutzerwunsch) — s. [WardenLockSession]-
     * Klassendoc. `by lazy` wie die übrigen App-weiten Instanzen; die eigentliche Invalidierung
     * über `ProcessLifecycleOwner` unten läuft unabhängig davon, ob diese Property je gelesen
     * wurde (die erste Lesung — spätestens `WardenStatusActivity.onResume()` — konstruiert sie). */
    val wardenLockSession: WardenLockSession by lazy { WardenLockSession() }

    override fun onCreate() {
        super.onCreate()
        // WardenLock: "keine Warden-Activity mehr sichtbar" (ON_STOP) unterscheidet ein echtes
        // Verlassen der App (Home, App-Wechsel, Bildschirmsperre) von reiner Navigation zwischen
        // Wardens eigenen Activities — Letzteres hält den Prozess laut ProcessLifecycleOwner
        // durchgehend im Vordergrund, s. WardenLockSession-Klassendoc.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    wardenLockSession.invalidate()
                }
            },
        )
        try {
            // Milestone "Manifest-Scan + Sofort-Benachrichtigung" (2026-08-21): POST_NOTIFICATIONS
            // ist ab Android 13 eine "gefährliche" Laufzeit-Berechtigung — als Device Owner kann
            // Warden sie sich selbst still gewähren (DevicePolicyManager.setPermissionGrantState),
            // ohne dass die Nutzerin einen Dialog bestätigen muss. Ohne DO (z. B. vor der
            // Provisionierung) schlägt der Aufruf fehl; das ist unkritisch — ohne die Berechtigung
            // zeigt Android eine geposteten Benachrichtigung laut Dokumentation kommentarlos
            // nicht an (kein Absturz), derselbe Fail-Safe-Charakter wie beim WorkManager-Catch
            // direkt darunter.
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(this, WardenDeviceAdminReceiver::class.java)
            if (dpm?.isDeviceOwnerApp(packageName) == true) {
                dpm.setPermissionGrantState(
                    admin,
                    packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }
        } catch (e: Exception) {
            Log.w("WardenApplication", "POST_NOTIFICATIONS-Selbstfreigabe fehlgeschlagen", e)
        }
        try {
            SuspiciousAppScanWorker.schedule(this)
        } catch (e: IllegalStateException) {
            // Realer Fund im Quellprojekt (s. dortiges WardenApplication-Klassendoc):
            // WorkManagers Lazy-Init-ContentProvider ist im frühen
            // ACTION_LOCKED_BOOT_COMPLETED-Fenster (Direct Boot) manchmal noch nicht bereit —
            // ohne dieses Catch crasht das die gesamte Application, bevor auch nur ein
            // BroadcastReceiver (inkl. der eigentlichen Boot-Reconciliation) laufen konnte.
            // Best-Effort: der nächste reguläre App-Start holt schedule() idempotent nach
            // (ExistingPeriodicWorkPolicy.KEEP). Dieser Scanner ist ohnehin Opt-in
            // (SuspiciousAppScanStore.isEnabled() default false), ein einmalig verpasster
            // schedule()-Aufruf ist also doppelt harmlos.
            Log.w(
                "WardenApplication",
                "SuspiciousAppScanWorker.schedule() übersprungen — " +
                    "WorkManager noch nicht bereit (vermutlich frühes Direct-Boot-Fenster)",
                e,
            )
        }
        try {
            // "Auto-Reboot nach Zeitfenster ohne Entsperren" (2026-08-22) — dieselbe
            // Best-Effort-Begründung wie beim SuspiciousAppScanWorker-Catch direkt darüber:
            // ohne gesetztes Zeitfenster (AutoRebootStorage.loadThresholdHours() == null) tut der
            // Worker bei jedem Lauf ohnehin nichts, ein einmalig verpasster schedule()-Aufruf ist
            // also ebenso harmlos.
            AutoRebootWorker.schedule(this)
        } catch (e: IllegalStateException) {
            Log.w(
                "WardenApplication",
                "AutoRebootWorker.schedule() übersprungen — " +
                    "WorkManager noch nicht bereit (vermutlich frühes Direct-Boot-Fenster)",
                e,
            )
        }
        try {
            // "USB automatisch sperren bei Bildschirmsperre" (2026-08-22, GrapheneOS-Vorbild) —
            // dieselbe Best-Effort-Begründung wie oben: ohne aktivierte Funktion
            // (UsbAutoLockStorage.isEnabled() == false, Default) tut der Worker bei jedem Lauf
            // ohnehin nichts.
            UsbAutoLockWorker.schedule(this)
        } catch (e: IllegalStateException) {
            Log.w(
                "WardenApplication",
                "UsbAutoLockWorker.schedule() übersprungen — " +
                    "WorkManager noch nicht bereit (vermutlich frühes Direct-Boot-Fenster)",
                e,
            )
        }
        try {
            UsbLockStateReceiver.syncRegistration(this)
        } catch (e: Exception) {
            Log.w("WardenApplication", "USB-Lock-Receiver-Registrierung übersprungen", e)
        }
        try {
            // Performance-Monitoring-Fenster (2026-08-25) — dieselbe Best-Effort-Begründung wie
            // oben: unabhängig davon, ob der Bildschirm je geöffnet wird, sammelt dieser Worker
            // laufend Batterie-Verlaufsdaten, damit beim ersten Öffnen bereits eine Drain-Rate
            // anzeigbar ist statt "keine Daten" bis zum übernächsten Intervall.
            BatterySamplingWorker.schedule(this)
        } catch (e: IllegalStateException) {
            Log.w(
                "WardenApplication",
                "BatterySamplingWorker.schedule() übersprungen — " +
                    "WorkManager noch nicht bereit (vermutlich frühes Direct-Boot-Fenster)",
                e,
            )
        }
    }
}

/** Process-wide audit log. Falls back to a fresh store only when [context] is not a Warden process
 * (instrumented tests constructing isolated files should keep calling the constructor directly). */
fun wardenAuditLog(context: Context): HashChainLogStore {
    val app = context.applicationContext
    return if (app is WardenApplication) {
        app.auditLogStore
    } else {
        HashChainLogStore(LogStorage.buildEnvelopeFile(app), wipeGuardAnchorFile = LogStorage.buildWipeGuardAnchorFile(app))
    }
}
