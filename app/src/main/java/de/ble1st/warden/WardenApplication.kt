package de.ble1st.warden

import android.Manifest
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import de.ble1st.warden.logging.HashChainLogStore
import de.ble1st.warden.netlock.NetLockdownController
import de.ble1st.warden.netlock.NetworkFirewallPolicyController
import de.ble1st.warden.logging.SecurityEventStorage
import de.ble1st.warden.logging.SecurityEventStore
import de.ble1st.warden.profile.AutoProfileWorker
import de.ble1st.warden.sim.SimChangeStartupWorker
import de.ble1st.warden.sim.SimChangeWorker
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
import de.ble1st.warden.sentinelbridge.SentinelWatchdogController
import de.ble1st.warden.usb.UsbAutoLockWorker
import de.ble1st.warden.usb.UsbLockStateReceiver
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * App-weite `Application`-Klasse. Gegenüber dem ConneXias-Framework-Quellprojekt fehlen weiterhin
 * mehrere dort vorhandene Cross-APK-Komponenten — `SuiteMembershipReconciliationWorker`
 * (Mitgliedschafts-Abgleich zwischen mehreren Geschwister-APKs, entfällt: nur noch zwei APKs,
 * Warden+Sentinel, kein Mehrparteien-Abgleich nötig). **`NetLockdownController`/
 * `FirewallPolicyController` (VPN/Barbican, "Netz-Sperre") sind seit 2026-08-27 kurzzeitig wieder
 * dagewesen, aber seit demselben Tag erneut pausiert** — ein im Live-Test gefundener, ungeklärter
 * Kernfehler (DNS-Blockliste/NAT-Relay verarbeitet auf einem frisch aufgebauten Tunnel keinen
 * Traffic mehr) machte die Feature-Verkabelung wieder rückgängig; der komplette Code liegt jetzt
 * unkompiliert geparkt unter `app/netlock-disabled/` (s. dortige README für Status/Reaktivierung).
 * `TrustedCallerAllowlist`
 * (Cross-APK-Zertifikatsprüfung via Userspace-Bus, entfällt: die neue Warden↔Sentinel-Kopplung
 * nutzt stattdessen von Android selbst durchgesetzte `signature`-Permissions, s.
 * [de.ble1st.warden.sentinelbridge.SentinelLockdownEngager]-Klassendoc "Warum kein AIDL-Bus").
 * `PackageUninstallProtectionSafeguard` für Geschwister-Suite-APKs ist seit dem Live-Drill-Fund
 * (2026-08-26) wieder da — [de.ble1st.warden.registry.SentinelUninstallProtectionSafeguard],
 * automatisch scharf geschaltet direkt bei Sentinels Silent-Install
 * ([de.ble1st.warden.appmanagement.SentinelInstallResultReceiver]).
 *
 * **`SentinelWatchdogController` ist seit "Sentinel: eigenständige Kiosk-PIN-App" (2026-08-26)
 * wieder da** — anders als in einer früheren Zwischenphase dieses Ports (Presence/PIN-Logik lief
 * dort kurzzeitig in Wardens eigenem Prozess) läuft Sentinels PIN-/Lock-Task-UI jetzt wieder in
 * einer eigenen, fremden APK, exakt wie im ConneXias-Framework-Quellprojekt — der
 * Cross-Process-Death-Watchdog ist damit wieder nötig, s. [sentinelWatchdogController].
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
    val auditLogStore: HashChainLogStore by lazy { LogStorage.buildAuditLogStore(this) }

    /** WardenLock (Finalisierungsphase, 2026-08-24, auf Nutzerwunsch) — s. [WardenLockSession]-
     * Klassendoc. `by lazy` wie die übrigen App-weiten Instanzen; die eigentliche Invalidierung
     * über `ProcessLifecycleOwner` unten läuft unabhängig davon, ob diese Property je gelesen
     * wurde (die erste Lesung — spätestens `WardenStatusActivity.onResume()` — konstruiert sie). */
    val wardenLockSession: WardenLockSession by lazy { WardenLockSession() }

    /** "System-Ereignisprotokoll" (2026-08-28) — Senke für die DPM-Sicherheits-/Netzwerk-Batches,
     * `by lazy` wie [auditLogStore]: der Keystore-Zugriff soll nicht schon in `onCreate()`
     * passieren, sondern erst beim ersten eintreffenden Batch bzw. beim Öffnen der Log-Einsicht. */
    val securityEventStore: SecurityEventStore by lazy {
        SecurityEventStore(SecurityEventStorage.buildEnvelopeFile(this))
    }

    /** "Sentinel: eigenständige Kiosk-PIN-App", Plan-Abschnitt "Watchdog-Sicherheitsnetz kommt in
     * v1 mit" — `by lazy` wie die übrigen App-weiten Instanzen: [SentinelDeathWatchdog][de.ble1st
     * .warden.sentinelbridge.SentinelDeathWatchdog] hält eine echte, lebende `bindService()`-
     * Verbindung, an eine Activity gebunden würde sie bei jedem Verlassen des Bildschirms verwaist
     * (weiterhin gebunden, aber ohne jede Referenz, um sie je wieder zu entschärfen) — s.
     * [SentinelWatchdogController]-Klassendoc. */
    val sentinelWatchdogController: SentinelWatchdogController by lazy { SentinelWatchdogController(this) }
    
    // "Netz-Sperre" (2026-08-29) — reaktiviert nach Fix des Kernfehlers
    val netLockdownController: NetLockdownController by lazy { NetLockdownController(this) }
    val networkFirewallPolicyController: NetworkFirewallPolicyController by lazy { NetworkFirewallPolicyController(this) }

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
        // Befund Q-8 (2026-08-29): alles ab hier läuft im Hintergrund statt auf dem Main-Thread.
        // Vorher standen hier ein Binder-Aufruf (setPermissionGrantState), sechs WorkManager-
        // schedule()-Aufrufe (je mit Datenbank-I/O) und eine Receiver-Registrierung — synchron, bei
        // *jedem* Prozessstart, auch wenn den Prozess nur ein Broadcast oder ein Worker hochzieht
        // und keine UI je sichtbar wird.
        //
        // Reihenfolge und Fehlerbehandlung bleiben identisch; der ProcessLifecycleOwner-Observer
        // oben bleibt bewusst synchron, weil er den WardenLock-Zustand absichert und keinerlei I/O
        // macht. Ein eigener Scope statt GlobalScope, damit die Herkunft der Coroutine im Log
        // erkennbar bleibt; kein Cancel-Pfad nötig — die Arbeit endet von selbst und lebt
        // definitionsgemäß so lange wie der Prozess.
        CoroutineScope(Dispatchers.IO).launch { runStartupTasks() }
    }

    private fun runStartupTasks() {
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
        try {
            // "SIM-Wechsel-Erkennung" (2026-08-28) — anders als die übrigen Worker zusätzlich ein
            // sofortiger Prüflauf beim Prozessstart: nach einem Neustart (und genau darum geht es
            // hier — SIM tauschen setzt in aller Regel einen Neustart voraus) läge der erste
            // periodische Lauf sonst bis zu 15 Minuten später. Der Aufruf ist billig und tut ohne
            // eingeschaltete Funktion gar nichts.
            //
            // Befund Q-6 (2026-08-29): dieser Sofortlauf rief bis hierher synchron
            // SimChangeController.checkAndMaybeReact() direkt hier auf — genau im frühen
            // Boot-Fenster, in dem die Carrier-Config häufig noch nicht geladen ist
            // (SimFingerprintReader/SimChangeStartupWorker-Klassendoc). Jetzt über
            // SimChangeStartupWorker mit kurzer Startverzögerung statt synchron und sofort.
            SimChangeWorker.schedule(this)
            SimChangeStartupWorker.scheduleOnce(this)
        } catch (e: Exception) {
            Log.w("WardenApplication", "SIM-Wechsel-Prüfung beim Start übersprungen", e)
        }
        try {
            // "Automatische Profilumschaltung" (2026-08-28) — nur planen, nicht sofort ausführen:
            // ein Profilwechsel schaltet bis zu 26 Safeguards um, das gehört nicht in den
            // Prozessstart-Pfad, sondern in den ohnehin laufenden periodischen Lauf.
            AutoProfileWorker.schedule(this)
        } catch (e: IllegalStateException) {
            Log.w(
                "WardenApplication",
                "AutoProfileWorker.schedule() übersprungen — " +
                    "WorkManager noch nicht bereit (vermutlich frühes Direct-Boot-Fenster)",
                e,
            )
        }
    }
}

/** Process-wide audit log. Falls back to a fresh store only when [context] is not a Warden process
 * (instrumented tests constructing isolated files should keep calling the constructor directly). */
/** Gegenstück zu [wardenAuditLog] für das System-Ereignisprotokoll (2026-08-28). */
fun wardenSecurityEvents(context: Context): SecurityEventStore {
    val app = context.applicationContext
    return if (app is WardenApplication) {
        app.securityEventStore
    } else {
        SecurityEventStore(SecurityEventStorage.buildEnvelopeFile(app))
    }
}

fun wardenAuditLog(context: Context): HashChainLogStore {
    val app = context.applicationContext
    return if (app is WardenApplication) {
        app.auditLogStore
    } else {
        LogStorage.buildAuditLogStore(app)
    }
}
