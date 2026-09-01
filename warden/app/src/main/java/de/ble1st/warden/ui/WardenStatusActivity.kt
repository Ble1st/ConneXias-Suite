package de.ble1st.warden.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ble1st.warden.BuildConfig
import de.ble1st.warden.R
import de.ble1st.warden.WardenApplication
import de.ble1st.warden.admin.DeviceOwnerStatusReader
import de.ble1st.warden.appmanagement.AppManagementInfo
import de.ble1st.warden.appmanagement.InstalledAppEntry
import de.ble1st.warden.appmanagement.InstalledAppLister
import de.ble1st.warden.appmanagement.PermissionAuditInfo
import de.ble1st.warden.appmanagement.PermissionAuditScanner
import de.ble1st.warden.domain.score.SecurityScoreBreakdown
import de.ble1st.warden.score.SecurityScoreCalculator
import de.ble1st.warden.score.SecurityScoreHistoryStore
import de.ble1st.warden.appmanagement.SentinelInstallStatus
import de.ble1st.warden.appmanagement.SentinelInstallStatusReader
import de.ble1st.warden.appmanagement.SentinelSilentInstaller
import de.ble1st.warden.appmanagement.SuspiciousAppFindingInfo
import de.ble1st.warden.autoreboot.AutoRebootStorage
import de.ble1st.warden.domain.cellsecurity.CellSecurityReaction
import de.ble1st.warden.domain.sim.SimChangeReaction
import de.ble1st.warden.domain.profile.AutoProfileConfig
import de.ble1st.warden.profile.AutoProfileStorage
import de.ble1st.warden.failedattempts.FailedAttemptsRebootStorage
import de.ble1st.warden.cellsecurity.CellSecurityController
import de.ble1st.warden.cellsecurity.CellSecurityStorage
import de.ble1st.warden.sim.SimChangeController
import de.ble1st.warden.sim.SimChangeStorage
import de.ble1st.warden.bus.ConcordBus
import de.ble1st.warden.domain.frp.FactoryResetProtectionAccounts
import de.ble1st.warden.domain.frp.FactoryResetProtectionDecision
import de.ble1st.warden.domain.performance.BatteryDrainDecision
import de.ble1st.warden.domain.pin.LockdownTriggerProfile
import de.ble1st.warden.domain.pin.LockdownTriggerProfilePolicy
import de.ble1st.warden.domain.presence.DestructiveCommandGuard
import de.ble1st.warden.domain.presence.SensitiveAction
import de.ble1st.warden.domain.presence.SensitiveActionDecisionResult
import de.ble1st.warden.domain.presence.SensitiveActionOutcome
import de.ble1st.warden.domain.appmanagement.ThreatSeverity
import de.ble1st.warden.domain.profile.WardenProfile
import de.ble1st.warden.performance.AppUsageInfo
import de.ble1st.warden.performance.AppUsageReader
import de.ble1st.warden.performance.BatteryHistoryStore
import de.ble1st.warden.performance.BatterySnapshot
import de.ble1st.warden.performance.BatteryStatusReader
import de.ble1st.warden.performance.DeviceMemoryReader
import de.ble1st.warden.performance.DeviceMemorySnapshot
import de.ble1st.warden.registry.FactoryResetProtectionSafeguard
import de.ble1st.warden.failsafe.FailsafeActivity
import de.ble1st.warden.integrity.DebuggableOsStatusReader
import de.ble1st.warden.integrity.DeviceIntegrityStatus
import de.ble1st.warden.pin.LockdownTriggerProfileStore
import de.ble1st.warden.pin.WardenLockScreenTextStorage
import de.ble1st.warden.pin.WardenLockTaskAutoEngageStore
import de.ble1st.warden.pin.WardenLockTaskDrillFreshnessGate
import de.ble1st.warden.pin.WardenLockTaskDrillStorage
import de.ble1st.warden.pin.WardenLockTaskPendingEngageStore
import de.ble1st.warden.registry.WardenLockTaskAuthorizer
import de.ble1st.warden.sentinelbridge.SentinelLockdownEngager
import de.ble1st.warden.sentinelbridge.SentinelPinStateStore
import de.ble1st.warden.presence.DestructiveActionExecutor
import de.ble1st.warden.presence.SensitiveActionActivity
import de.ble1st.warden.presence.WardenLockActivity
import de.ble1st.warden.presence.WardenPinActivity
import de.ble1st.warden.registry.AccessibilityLockdownSafeguard
import de.ble1st.warden.registry.AutoLockTimeoutSafeguard
import de.ble1st.warden.registry.BackupServiceLockdownSafeguard
import de.ble1st.warden.registry.CameraSafeguard
import de.ble1st.warden.registry.ForceStopProtectionSafeguard
import de.ble1st.warden.registry.InputMethodLockdownSafeguard
import de.ble1st.warden.registry.KeyguardHardeningSafeguard
import de.ble1st.warden.registry.LockScreenInfoManager
import de.ble1st.warden.registry.LockScreenPrivacySafeguard
import de.ble1st.warden.registry.NetworkLoggingSafeguard
import de.ble1st.warden.registry.OrganizationNameManager
import de.ble1st.warden.registry.PasswordComplexitySafeguard
import de.ble1st.warden.registry.ScreenCaptureSafeguard
import de.ble1st.warden.registry.SecurityLoggingSafeguard
import de.ble1st.warden.registry.SelfUninstallProtectionSafeguard
import de.ble1st.warden.registry.SupportMessageManager
import de.ble1st.warden.registry.SystemUpdatePolicySafeguard
import de.ble1st.warden.registry.SentinelUninstallProtectionSafeguard
import de.ble1st.warden.registry.UsbDataSignalingSafeguard
import de.ble1st.warden.registry.UserRestrictionSafeguard
import de.ble1st.warden.registry.WardenFactoryResetProtectionStorage
import de.ble1st.warden.registry.WardenOrganizationNameStorage
import de.ble1st.warden.registry.WardenSupportMessageStorage
import de.ble1st.warden.domain.netlock.ChildVpnConfigParseError
import de.ble1st.warden.domain.netlock.ChildVpnConfigParseException
import de.ble1st.warden.domain.netlock.ChildVpnConfigParser
import de.ble1st.warden.netlock.ChildVpnConfigStore
import de.ble1st.warden.netlock.DomainBlocklistStore
import de.ble1st.warden.netlock.FirewallMode
import de.ble1st.warden.netlock.NetworkFirewallPolicyController
import de.ble1st.warden.netlock.NetLockdownController
import de.ble1st.warden.ui.NetworkScreen
import de.ble1st.warden.ui.theme.WardenAccent
import de.ble1st.warden.ui.theme.WardenTheme
import de.ble1st.warden.ui.theme.WardenThemePrefs
import de.ble1st.warden.wardenAuditLog
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wardens Haupt-UI — portiert aus dem ConneXias-Framework-Quellprojekt (`WardenStatusActivity`),
 * stark gekürzt gegenüber der dortigen Fassung: kein Sentinel-Wächter-Schalter (Sentinel läuft
 * jetzt in diesem Prozess, kein separater Cross-Process-Wächter mehr, s. Plan-Abschnitt
 * "Presence: Sentinels PIN-Logik portiert"), keine Netz-Sperre (Barbican/VPN entfällt
 * vollständig), keine sechs Deinstallationsschutz-Schalter (keine Geschwister-Suite-APKs mehr zu
 * schützen), kein App-Update-Button (Silent-Update war nur für Sentinel-Downgrade-Schutz
 * gedacht), kein "Herald öffnen"-Button (Herald ist keine separate APK mehr — die Herald-Screens
 * sind direkt hier über [AppManagementScreen]/[SecurityScannerScreen] eingebaut, s.
 * Plan-Abschnitt "Herald-UI einbauen").
 *
 * **Dashboard = gruppiertes Menü, keine Button-Liste mehr** (s. UI-Review 2026-08-21): die
 * Statuskarte (DO-Status, Version, Debuggable-Warnung) bleibt oben, darunter vier Abschnitte
 * (Geräteschutz/App-Sicherheit/Zugriff & Bestätigung/Wiederherstellung) mit je einer
 * [MenuRow] pro Ziel statt sechs gleichrangiger [androidx.compose.material3.Button]s. Der
 * Kamera-Schalter, vorher direkt auf dem Dashboard, steht jetzt im
 * [SafeguardsScreen]-Untermenü (zusammen mit [de.ble1st.warden.registry.ScreenCaptureSafeguard],
 * das bisher gar keinen UI-Schalter hatte — s. dortiges Klassendoc); der Akzentfarb-Umschalter im
 * [SettingsScreen]-Untermenü, erreichbar über das Zahnrad in der TopAppBar statt als weiterer
 * Menüpunkt zwischen den Sicherheits-Zielen. Direkter [CameraSafeguard]-Aufruf ohne Umweg über
 * [ConcordBus] bleibt dabei unverändert (unverändert aus dem Quellprojekt übernommen, s.
 * [SafeguardsScreen]-Klassendoc für die Begründung) — nur die UI-Zeile ist umgezogen, nicht die
 * Verkabelung.
 *
 * **Navigation zu allen Untermenüs:** einfache lokale Bildschirm-Zustands-Umschaltung
 * ([WardenScreen]) statt einer `androidx.navigation`-Abhängigkeit — bei fünf lokalen Zielen
 * (Status, App-Verwaltung, Sicherheits-Scanner, Safeguards, Einstellungen) rechtfertigt das
 * weiterhin keine zusätzliche Bibliothek. Jeder Screen ruft [ConcordBus] direkt und synchron auf
 * (kein Binder-Overhead mehr, s. [AppManagementScreen]-Klassendoc) und lädt seine Daten nach
 * jeder Mutation neu — dieselbe Fail-Safe-Haltung wie beim Kamera-Schalter: die UI zeigt immer
 * den tatsächlich gelesenen Zustand, nie einen nur angenommenen.
 */
class WardenStatusActivity : ComponentActivity() {

    // WardenLock (Finalisierungsphase 2026-08-24) — Activity-gehaltener Compose-State, damit
    // sowohl onResume() (außerhalb der Composition) als auch der Composable-Baum unten denselben
    // Zustand sehen: rendert erst WardenRoot, nachdem WardenLockActivity RESULT_OK geliefert hat.
    private val authenticated = mutableStateOf(false)
    private var lockRequestInFlight = false
    private val wardenLockSession by lazy { (application as WardenApplication).wardenLockSession }

    // "Lockdown-Auslöse-Profil" (2026-08-27) — hält den Grund einer per SentinelQuickTile
    // vorgemerkten Anforderung mit `requiresConfirmation=true` (LockdownTriggerProfile.STANDARD),
    // bis der Ja/Nein-Dialog unten in setContent { } beantwortet ist. `null` = kein offener
    // Dialog.
    private val pendingKioskConfirmation = mutableStateOf<String?>(null)

    private val lockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        lockRequestInFlight = false
        if (result.resultCode == RESULT_OK) {
            authenticated.value = true
            consumePendingLockTaskEngage()
        } else {
            // Zurück-Geste/abgebrochener Prompt auf WardenLockActivity — ohne Nachweis gibt es
            // nichts sinnvoll anzuzeigen, kein Fallback auf einen ungesicherten Zustand.
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Architektur-Review 2026-08-24 (F-6): macht explizit, was ab targetSdk 35 ohnehin gilt
        // (Edge-to-Edge ist ab API 35 erzwungen, unabhängig davon, ob die App das anfordert) —
        // ohne den expliziten Aufruf verlässt sich die App auf das reine System-Default-Verhalten
        // für Insets/Icon-Kontrast statt es selbst zu deklarieren.
        enableEdgeToEdge()
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val isDeviceOwner = DeviceOwnerStatusReader(applicationContext).isDeviceOwner()
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

        val osStatus = DebuggableOsStatusReader()
        val isDebuggableOs = osStatus.isDebuggableOs()
        if (isDebuggableOs) {
            // Konzept 2b/(5): sichtbare Warnung geloggt UND angezeigt — Loggen hier, Anzeige unten.
            Log.w(
                TAG,
                "Debuggable OS erkannt (Build.TYPE=${osStatus.buildType()}) — " +
                    "das Vertrauensmodell setzt ein user-Build voraus (Konzept 2b/(5)).",
            )
        }

        val concordBus = (application as WardenApplication).concordBus
        // "Lockdown-Auslöse-Profil" (2026-08-27) — eigener, wegwerfbarer Executor nur für den
        // Dashboard-Button "Kiosk jetzt" (Zeile in WardenRoot unten), analog zu
        // SensitiveActionActivity.buildExecutor(): frische Instanz, frischer Rate-Limit-Eimer, s.
        // dortigen Kommentar zur "fresh instance"-Konvention. Die vier anderen performX-Lambdas
        // bleiben auf ihren Defaults — dieser Executor wird nie mit einer anderen Aktion als
        // LOCKDOWN_TASK_ENGAGE aufgerufen.
        val kioskExecutor = DestructiveActionExecutor(
            isDebugBuild = BuildConfig.DEBUG,
            logStore = wardenAuditLog(applicationContext),
            performLockTaskEngage = {
                SentinelLockdownEngager.engage(
                    context = applicationContext,
                    emergencyCallDrillPassed = WardenLockTaskDrillFreshnessGate.effectiveEmergencyCallDrillPassed(applicationContext),
                )
            },
        )

        setContent {
            // Nur die Wahl selbst lebt hier im Compose-State (reiner Anzeige-State, s.
            // WardenThemePrefs-Klassendoc) — der geladene Startwert kommt aus SharedPreferences,
            // jede Änderung wird sofort zurückgeschrieben, damit die nächste Activity (z. B.
            // SensitiveActionActivity, per eigenem WardenThemePrefs.load()) denselben Akzent sieht.
            var accent by remember { mutableStateOf(WardenThemePrefs.load(applicationContext)) }
            // Startwert bevorzugt live von der DPM gelesen (derselbe "nie gecacht"-Grundsatz wie
            // Safeguard.isActive(), s. LockScreenInfoManager-Klassendoc) — nur falls das scheitert
            // (z. B. kein Device Owner mehr) fällt es auf den zuletzt persistierten Soll-Wert
            // zurück, rein zur Anzeige, kein Fail-Safe-Fall wie beim PIN-Blob.
            var lockScreenText by remember {
                mutableStateOf(
                    runCatching { LockScreenInfoManager(applicationContext).current() }
                        .getOrElse { WardenLockScreenTextStorage.load(applicationContext) },
                )
            }
            // Dieselbe Lade-Begründung wie lockScreenText — unabhängiger DPM-Wert, s.
            // OrganizationNameManager-Klassendoc.
            var organizationName by remember {
                mutableStateOf(
                    runCatching { OrganizationNameManager(applicationContext).current() }
                        .getOrElse { WardenOrganizationNameStorage.load(applicationContext) },
                )
            }
            // Tier 6 (2026-08-22) — dieselbe Lade-Begründung wie lockScreenText/organizationName.
            var supportMessage by remember {
                mutableStateOf(
                    runCatching { SupportMessageManager(applicationContext).current() }
                        .getOrElse { WardenSupportMessageStorage.load(applicationContext) },
                )
            }
            // Auto-Reboot-Zeitfenster (2026-08-22, auf Nutzerwunsch) — anders als die drei DPM-
            // Felder oben kein DPM-Live-Wert, sondern reiner lokaler Soll-Wert
            // (AutoRebootStorage-Klassendoc), also direktes Laden ohne runCatching-Fallback.
            var autoRebootThresholdHours by remember {
                mutableStateOf(AutoRebootStorage.loadThresholdHours(applicationContext))
            }
            // "Neustart nach zu vielen Fehlversuchen" (2026-08-28) — dieselbe reine
            // Soll-Wert-Verkabelung wie das Auto-Reboot-Zeitfenster darüber. Der
            // Sperrbildschirm-Zustand ist dagegen ein Live-Wert: ohne gesetzten Code meldet
            // Android keine Fehlversuche, die Einstellung wäre wirkungslos (Warnhinweis im Feld).
            var failedAttemptsRebootThreshold by remember {
                mutableStateOf(FailedAttemptsRebootStorage.loadThreshold(applicationContext))
            }
            // "SIM-Wechsel-Erkennung" (2026-08-28) — reiner Soll-Wert wie die beiden darüber.
            var simChangeReaction by remember {
                mutableStateOf(SimChangeStorage.loadReaction(applicationContext))
            }
            // "Mobilfunkzellen-Auffälligkeitserkennung" (2026-08-29) — reiner Soll-Wert,
            // dieselbe Verkabelung wie simChangeReaction darüber.
            var cellSecurityReaction by remember {
                mutableStateOf(CellSecurityStorage.loadReaction(applicationContext))
            }
            // "Automatische Profilumschaltung" (2026-08-28) — reiner Soll-Wert wie die übrigen
            // Härtungs-Felder; angewendet wird ausschließlich vom periodischen Worker.
            var autoProfileConfig by remember {
                mutableStateOf(AutoProfileStorage.load(applicationContext))
            }
            val secureLockScreenConfigured = remember {
                runCatching {
                    applicationContext.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
                }.getOrDefault(true)
            }
            val isAuthenticated by authenticated
            // "Lockdown-Auslöse-Profil" (2026-08-27) — Geschwister von WardenTheme(...) statt
            // innerhalb (und damit außerhalb des `!isAuthenticated`-Kurzschlusses oben): eine per
            // SentinelQuickTile vorgemerkte Bestätigungsanfrage (LockdownTriggerProfile.STANDARD)
            // muss erscheinen können, sobald wieder authentifiziert ist, unabhängig davon, welcher
            // WardenScreen-Unterbildschirm gerade offen ist.
            val kioskConfirmReason by pendingKioskConfirmation
            if (kioskConfirmReason != null) {
                AlertDialog(
                    onDismissRequest = {
                        wardenAuditLog(applicationContext).append(
                            Log.INFO,
                            TAG,
                            "Lock-Task-Anforderung abgebrochen: $kioskConfirmReason",
                        )
                        pendingKioskConfirmation.value = null
                    },
                    title = { Text(stringResource(R.string.kiosk_confirm_title)) },
                    text = {
                        Text("$kioskConfirmReason\n\n" + stringResource(R.string.kiosk_confirm_body))
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val reason = kioskConfirmReason!!
                            pendingKioskConfirmation.value = null
                            performPendingLockTaskEngage(reason)
                        }) { Text(stringResource(R.string.action_yes)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingKioskConfirmation.value = null }) { Text(stringResource(R.string.action_no)) }
                    },
                )
            }
            WardenTheme(accent = accent) {
                if (!isAuthenticated) {
                    // WardenLockActivity läuft parallel (onResume() oben) — hier nur ein
                    // Platzhalter, kein Dashboard-Inhalt darf davor sichtbar sein/gerendert werden.
                    LoadingScreen(title = stringResource(R.string.app_name), onBack = { finish() })
                    return@WardenTheme
                }
                WardenRoot(
                    isDeviceOwner = isDeviceOwner,
                    versionName = versionName,
                    isDebuggableOs = isDebuggableOs,
                    buildType = osStatus.buildType(),
                    // Ungeschützt hier hart abstürzen zu lassen wäre real gefährlich: geht der DO
                    // je verloren (z. B. Testgerät, dpm remove-active-admin), existiert admin für
                    // getCameraDisabled() nicht mehr -> SecurityException noch vor dem ersten
                    // Compose-Frame, die App crash-loopt komplett (2026-08-21, real auf dem A15
                    // reproduziert). Dieselbe Fail-Safe-Haltung wie loadManagedAppsSafely & Co.:
                    // ein Lesefehler degradiert zu einem sicheren Default statt die UI zu begraben.
                    concordBus = concordBus,
                    onOpenFailsafe = { startActivity(Intent(this, FailsafeActivity::class.java)) },
                    onOpenSensitiveAction = { startActivity(Intent(this, SensitiveActionActivity::class.java)) },
                    // "Lockdown-Auslöse-Profil" (2026-08-27) — Dashboard-Button "Kiosk jetzt"
                    // (Zeile in WardenStatusScreen). `wardenLockSession.isAuthenticated()` live am
                    // Klick gelesen statt hart `true` — dieselbe Defense-in-Depth wie
                    // SensitiveActionActivity.onConfirmSession, auch wenn WardenStatusScreen
                    // ohnehin erst nach isAuthenticated() gerendert wird (identische Begründung
                    // wie beim bereits ungegateten onLockNow unten).
                    onKioskNow = {
                        val outcome = kioskExecutor.executeWithSessionPresence(
                            SensitiveAction.LOCKDOWN_TASK_ENGAGE,
                            SensitiveAction.LOCKDOWN_TASK_ENGAGE.confirmationPhrase,
                            sessionAuthenticated = wardenLockSession.isAuthenticated(),
                        )
                        outcome
                    },
                    // Kein EXTRA_PRESENCE_REQUEST — echte Ersteinrichtung/Verify/PIN-Änderung, im
                    // Presence-Request-Modus verweigert WardenPinActivity das bewusst (Klassendoc).
                    // Ohne diesen Einstiegspunkt gäbe es gar keinen Weg mehr, überhaupt eine PIN
                    // einzurichten, seit WardenPinActivity keine eigene Launcher-Activity mehr ist
                    // (anders als Sentinels ursprüngliche SentinelActivity).
                    onOpenPinManagement = { startActivity(Intent(this, WardenPinActivity::class.java)) },
                    onOpenLog = {
                        try {
                            concordBus.requestLogAccess()
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Log-Zugriff abgelehnt", e)
                        }
                    },
                    accent = accent,
                    onAccentChange = { selected ->
                        accent = selected
                        WardenThemePrefs.save(applicationContext, selected)
                    },
                    lockScreenText = lockScreenText,
                    onLockScreenTextChange = { updated ->
                        lockScreenText = updated
                        // Soll-Wert zuerst persistieren (überlebt auch, falls apply() unten
                        // scheitert — RegistryReconciliationReceiver appliziert ihn dann beim
                        // nächsten Boot erneut), dann live setzen.
                        WardenLockScreenTextStorage.save(applicationContext, updated)
                        try {
                            LockScreenInfoManager(applicationContext).apply(updated)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Sperrbildschirm-Text setzen fehlgeschlagen (kein Device Owner mehr?)", e)
                        }
                    },
                    organizationName = organizationName,
                    onOrganizationNameChange = { updated ->
                        organizationName = updated
                        WardenOrganizationNameStorage.save(applicationContext, updated)
                        try {
                            OrganizationNameManager(applicationContext).apply(updated)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Organisationsname setzen fehlgeschlagen (kein Device Owner mehr?)", e)
                        }
                    },
                    supportMessage = supportMessage,
                    onSupportMessageChange = { updated ->
                        supportMessage = updated
                        WardenSupportMessageStorage.save(applicationContext, updated)
                        try {
                            SupportMessageManager(applicationContext).apply(updated)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Support-Hinweis setzen fehlgeschlagen (kein Device Owner mehr?)", e)
                        }
                    },
                    autoProfileConfig = autoProfileConfig,
                    onAutoProfileConfigChange = { updated ->
                        // Vollständig abgeschaltet: den Merker "zuletzt automatisch gesetzt"
                        // verwerfen, sonst täte der erste Lauf nach dem Wiedereinschalten nichts.
                        if (!updated.isEnabled) {
                            AutoProfileStorage.clearLastApplied(applicationContext)
                        }
                        autoProfileConfig = updated
                        AutoProfileStorage.save(applicationContext, updated)
                    },
                    simChangeReaction = simChangeReaction,
                    onSimChangeReactionChange = { updated ->
                        // Ausschalten verwirft die Baseline: sonst gälte beim Wiedereinschalten
                        // jeder Tausch aus der unbeobachteten Zwischenzeit sofort als Wechsel.
                        if (updated == null) {
                            SimChangeStorage.clearBaseline(applicationContext)
                        }
                        simChangeReaction = updated
                        SimChangeStorage.saveReaction(applicationContext, updated)
                        // Erste Messung sofort holen, damit die Baseline steht, solange das Gerät
                        // nachweislich noch in der richtigen Hand ist.
                        if (updated != null) {
                            runCatching { SimChangeController(applicationContext).checkAndMaybeReact(BuildConfig.DEBUG) }
                                .onFailure { Log.w(TAG, "SIM-Baseline konnte nicht sofort gesetzt werden", it) }
                        }
                    },
                    cellSecurityReaction = cellSecurityReaction,
                    onCellSecurityReactionChange = { updated ->
                        // Dieselbe Begründung wie bei onSimChangeReactionChange oben: Ausschalten
                        // verwirft den letzten Messwert, sonst gälte beim Wiedereinschalten die
                        // erste gemessene Zelle sofort als Auffälligkeit.
                        if (updated == null) {
                            CellSecurityStorage.clearObservation(applicationContext)
                        }
                        cellSecurityReaction = updated
                        CellSecurityStorage.saveReaction(applicationContext, updated)
                        if (updated != null) {
                            runCatching { CellSecurityController(applicationContext).checkAndMaybeReact(BuildConfig.DEBUG) }
                                .onFailure { Log.w(TAG, "Zellen-Baseline konnte nicht sofort gesetzt werden", it) }
                        }
                    },
                    failedAttemptsRebootThreshold = failedAttemptsRebootThreshold,
                    secureLockScreenConfigured = secureLockScreenConfigured,
                    onFailedAttemptsRebootThresholdChange = { updated ->
                        // Beim Ein-/Umschalten den laufenden Zähler verwerfen — ein alter Stand
                        // aus einer früheren Konfiguration darf nicht sofort auslösen.
                        FailedAttemptsRebootStorage.resetFailedAttempts(applicationContext)
                        failedAttemptsRebootThreshold = updated
                        FailedAttemptsRebootStorage.saveThreshold(applicationContext, updated)
                    },
                    autoRebootThresholdHours = autoRebootThresholdHours,
                    onAutoRebootThresholdHoursChange = { updated ->
                        // Aktivierung (vorher null, jetzt gesetzt): Baseline sofort auf jetzt setzen —
                        // s. AutoRebootController-Klassendoc, verhindert einen sofortigen Reboot aus
                        // echter Ungewissheit heraus direkt nach dem Einschalten der Funktion.
                        if (autoRebootThresholdHours == null && updated != null) {
                            AutoRebootStorage.saveLastSeenUnlockedMillis(applicationContext, System.currentTimeMillis())
                        }
                        autoRebootThresholdHours = updated
                        AutoRebootStorage.saveThresholdHours(applicationContext, updated)
                    },
                )
            }
        }
    }

    // WardenLock (Finalisierungsphase 2026-08-24, auf Nutzerwunsch): "jedem App-Start/Resume" als
    // Trigger — läuft bei jedem Resume, nicht nur beim Kaltstart. WardenLockSession invalidiert
    // sich selbst nur über ProcessLifecycleOwner (echtes Verlassen der App), nicht bei jeder
    // Navigation — dieser Check hier bleibt trotzdem bei jedem Resume nötig, weil das der einzige
    // Ort ist, an dem der aktuelle Sitzungsstatus tatsächlich abgefragt wird (s.
    // WardenLockSession-Klassendoc).
    override fun onResume() {
        super.onResume()
        if (wardenLockSession.isAuthenticated()) {
            authenticated.value = true
            consumePendingLockTaskEngage()
            return
        }
        authenticated.value = false
        if (!lockRequestInFlight) {
            lockRequestInFlight = true
            lockLauncher.launch(Intent(this, WardenLockActivity::class.java))
        }
    }

    /**
     * "LockMode/Threat-Protection-Ausbau" (2026-08-25) — der Abholpunkt für
     * [de.ble1st.warden.pin.WardenLockTaskPendingEngageStore]s Klassendoc: die nächste
     * Gelegenheit, in der diese Activity wieder authentifiziert im Vordergrund läuft (Kaltstart
     * über [WardenLockActivity] **oder** ein bereits gültiger [WardenLockSession]-Nachweis bei
     * jedem Resume — beide Pfade oben rufen dies auf, kein dritter Ort nötig), holt eine
     * ausstehende Anforderung ab und stößt [SentinelLockdownEngager.engage] an. Seit "Sentinel:
     * eigenständige Kiosk-PIN-App" scharfschaltet dieser Aufruf Sentinels Paket, nicht mehr
     * Wardens eigenes — s. dessen Klassendoc.
     * [WardenLockTaskPendingEngageStore.consumeIfPending] ist ein No-Op (liefert `null`), solange
     * nichts aussteht — dieser Aufruf bei jedem Resume ist deshalb unkritisch billig.
     */
    private fun consumePendingLockTaskEngage() {
        val pending = WardenLockTaskPendingEngageStore.consumeIfPending(applicationContext) ?: return
        // "Lockdown-Auslöse-Profil" (2026-08-27): SentinelQuickTile markiert eine Anforderung
        // unter LockdownTriggerProfile.STANDARD mit requiresConfirmation=true — statt sofort zu
        // feuern, zeigt setContent { } unten erst den Ja/Nein-Dialog; der eigentliche Aufruf
        // erfolgt dann über performPendingLockTaskEngage() aus dessen Bestätigen-Button.
        if (pending.requiresConfirmation) {
            pendingKioskConfirmation.value = pending.reason
            return
        }
        performPendingLockTaskEngage(pending.reason)
    }

    /** Der eigentliche Ausführungs-Schritt, getrennt von [consumePendingLockTaskEngage] — wird
     * entweder direkt von dort (kein Bestätigungsdialog nötig: kritischer Bedrohungsfund,
     * `LockdownTriggerProfile.FAST`) oder erst nach "Ja" im Kachel-Bestätigungsdialog aufgerufen
     * (`LockdownTriggerProfile.STANDARD`). */
    private fun performPendingLockTaskEngage(reason: String) {
        // Derselbe unbedingte Debug-Build-Hardblock wie bei jeder anderen realen DPM-Aktion
        // dieses Projekts (DestructiveCommandGuard, F.4) — SentinelLockdownEngager prüft selbst
        // nur, ob Sentinel überhaupt installiert ist, nicht diesen Guard, muss also hier vom
        // Aufrufer davorgeschaltet werden, genau wie DestructiveActionExecutor.executeInternal es
        // für den manuellen SensitiveAction.LOCKDOWN_TASK_ENGAGE-Weg bereits tut.
        val started = DestructiveCommandGuard.isExecutionAllowed(BuildConfig.DEBUG) &&
            runCatching {
                SentinelLockdownEngager.engage(
                    context = applicationContext,
                    emergencyCallDrillPassed = WardenLockTaskDrillFreshnessGate.effectiveEmergencyCallDrillPassed(applicationContext),
                )
            }.onFailure { Log.e(TAG, "Lock-Task-Auto-Engage fehlgeschlagen", it) }.getOrDefault(false)
        wardenAuditLog(applicationContext).append(
            priority = Log.WARN,
            tag = TAG,
            message = if (started) {
                "Lock-Task automatisch aktiviert: $reason"
            } else {
                "Lock-Task-Auto-Engage angefordert, aber Gate verweigert (Debug-Build und/oder " +
                    "Drill nicht bestätigt/abgelaufen): $reason"
            },
        )
    }

    private companion object {
        const val TAG = "WardenStatus"
    }
}

private sealed class WardenScreen {
    data object Status : WardenScreen()
    data object AppManagement : WardenScreen()
    data object SecurityScanner : WardenScreen()
    data object Safeguards : WardenScreen()
    data object Settings : WardenScreen()
    data object PermissionAudit : WardenScreen()
    data object PerformanceMonitor : WardenScreen()
    data object Network : WardenScreen()
    data object SecurityScore : WardenScreen()
}

/** Architektur-Review 2026-08-24 (F-3) — `WardenScreen` selbst ist keine der sonst über
 * `rememberSaveable`/`autoSaver()` automatisch sicherbaren Typen (kein primitiver Typ, kein
 * `Parcelable`), daher ein manueller [Saver]: sichert nur den einfachen Klassennamen, `restore`
 * bildet ihn zurück auf die passende `data object`-Instanz. Ein unbekannter/fehlender Name (sollte
 * nie vorkommen, aber s. Fail-Safe-Grundsatz der Datei) fällt auf [WardenScreen.Status] zurück —
 * dieselbe Richtung, in die die App beim allerersten Start ohnehin startet, kein neuer Fehlerfall. */
private val WardenScreenSaver: Saver<WardenScreen, String> = Saver(
    save = { it::class.simpleName ?: "Status" },
    restore = { name ->
        when (name) {
            "AppManagement" -> WardenScreen.AppManagement
            "SecurityScanner" -> WardenScreen.SecurityScanner
            "Safeguards" -> WardenScreen.Safeguards
            "Settings" -> WardenScreen.Settings
            "PermissionAudit" -> WardenScreen.PermissionAudit
            "PerformanceMonitor" -> WardenScreen.PerformanceMonitor
            // "Network" fehlte hier bislang (Bestandslücke, beim Ergänzen von "SecurityScore"
            // 2026-08-29 mitkorrigiert) — ohne diesen Zweig fiel eine Prozess-Tod-Wiederherstellung
            // auf dem Netzwerk-Bildschirm fälschlich auf WardenScreen.Status zurück.
            "Network" -> WardenScreen.Network
            "SecurityScore" -> WardenScreen.SecurityScore
            else -> WardenScreen.Status
        }
    },
)

@Composable
private fun WardenRoot(
    isDeviceOwner: Boolean,
    versionName: String,
    isDebuggableOs: Boolean,
    buildType: String,
    concordBus: ConcordBus,
    onOpenFailsafe: () -> Unit,
    onOpenSensitiveAction: () -> Unit,
    onOpenPinManagement: () -> Unit,
    onOpenLog: () -> Unit,
    onKioskNow: () -> SensitiveActionOutcome,
    accent: WardenAccent,
    onAccentChange: (WardenAccent) -> Unit,
    lockScreenText: String?,
    onLockScreenTextChange: (String?) -> Unit,
    organizationName: String?,
    onOrganizationNameChange: (String?) -> Unit,
    supportMessage: String?,
    onSupportMessageChange: (String?) -> Unit,
    autoRebootThresholdHours: Int?,
    onAutoRebootThresholdHoursChange: (Int?) -> Unit,
    failedAttemptsRebootThreshold: Int?,
    secureLockScreenConfigured: Boolean,
    onFailedAttemptsRebootThresholdChange: (Int?) -> Unit,
    simChangeReaction: SimChangeReaction?,
    onSimChangeReactionChange: (SimChangeReaction?) -> Unit,
    cellSecurityReaction: CellSecurityReaction?,
    onCellSecurityReactionChange: (CellSecurityReaction?) -> Unit,
    autoProfileConfig: AutoProfileConfig,
    onAutoProfileConfigChange: (AutoProfileConfig) -> Unit,
) {
    // rememberSaveable statt remember (Architektur-Review 2026-08-24, F-3): ohne das landete man
    // nach jeder Konfigurationsänderung (Rotation, Falt-/Split-Screen-Vorgang) unabhängig vom
    // vorherigen Unterbildschirm wieder auf WardenScreen.Status — s. [WardenScreenSaver]-Doc.
    var screen by rememberSaveable(stateSaver = WardenScreenSaver) {
        mutableStateOf<WardenScreen>(WardenScreen.Status)
    }

    // Architektur-Review 2026-08-24 (F-1): ohne eigenen BackHandler ruft Android ab targetSdk 36
    // bei aktiviertem Predictive Back (s. android:enableOnBackInvokedCallback im Manifest) kein
    // onBackPressed()/KEYCODE_BACK mehr auf — die Zurück-Geste hätte aus jedem Unterbildschirm
    // sofort die ganze App verlassen statt zum Status-Screen zurückzukehren. `enabled` nur auf den
    // Unterbildschirmen aktiv, damit ein BACK auf dem Status-Screen selbst weiterhin normal die
    // App verlässt (Standardverhalten der Launcher-Activity).
    BackHandler(enabled = screen != WardenScreen.Status) {
        screen = WardenScreen.Status
    }

    when (screen) {
        WardenScreen.Status -> {
            // Nur für das Funde-Badge auf dem "Sicherheits-Scanner"-Menüpunkt geladen — dieselbe
            // Fail-Safe-Haltung wie überall sonst hier: ein Lesefehler zeigt kein Badge statt
            // einer irreführenden "0". `remember`/`LaunchedEffect` laden frisch bei jedem
            // Wieder-Betreten von WardenScreen.Status (Compose verwirft den Branch-State beim
            // Verlassen), also z. B. auch direkt nach dem Zurückkommen aus dem Scanner-Screen.
            // Architektur-Review 2026-08-24 (F-2): asynchron statt synchron im Composition-Body —
            // liest den EnvelopeFile (Keystore-Unwrap + AES-GCM-Entschlüsselung), keine
            // Main-Thread-blockierende Operation mehr. Startwert `null` fällt hier nicht mit
            // "Lesen fehlgeschlagen" zusammen wie sonst in dieser Datei: bis zum ersten Frame nach
            // dem Laden zeigt das Badge kurz "!" statt der echten Zahl — für ein reines
            // Zähler-Badge auf dem Dashboard (kein Fehlerhinweis, kein Schalter) hinnehmbar, anders
            // als bei den vollflächigen Lade-Gates unten (AppManagement/SecurityScanner).
            var findingsResult by remember { mutableStateOf<List<SuspiciousAppFindingInfo>?>(null) }
            LaunchedEffect(Unit) {
                findingsResult = withContext(Dispatchers.IO) { loadFindingsSafely(concordBus) }
            }
            // "Lockdown-Auslöse-Profil" (2026-08-27) — Sichtbarkeit des Dashboard-Buttons "Kiosk
            // jetzt": nur bei Profil ≠ STRICT, installiertem Sentinel und bestätigtem Notruf-Drill.
            // Frisch bei jedem Wieder-Betreten dieses Zweigs gelesen (dieselbe Begründung wie
            // findingsResult oben), kein Cross-Activity-Refresh-Mechanismus nötig.
            val statusContext = LocalContext.current.applicationContext
            // Vorschlag V-1 (2026-08-29): dasselbe "zuletzt angewandte Profil" wie im
            // Safeguards-Bildschirm, hier auf der Statuskarte. Reines SharedPreferences-Lesen,
            // kein DPM-Aufruf — deshalb ohne LaunchedEffect/IO-Dispatcher, anders als
            // findingsResult darüber.
            val activeProfile = remember { AutoProfileStorage.loadLastEffective(statusContext) }
            val lockdownTriggerProfile = remember { LockdownTriggerProfileStore.load(statusContext) }
            val kioskQuickTriggerVisible = remember(lockdownTriggerProfile) {
                LockdownTriggerProfilePolicy.quickTriggerEntryPointsEnabled(lockdownTriggerProfile) &&
                    SentinelInstallStatusReader(statusContext).currentStatus() is SentinelInstallStatus.Installed &&
                    WardenLockTaskDrillStorage.isConfirmed(statusContext)
            }
            WardenStatusScreen(
                isDeviceOwner = isDeviceOwner,
                versionName = versionName,
                isDebuggableOs = isDebuggableOs,
                buildType = buildType,
                suspiciousFindingCount = findingsResult?.size ?: 0,
                findingsLoadFailed = findingsResult == null,
                // Vorschlag V-2 (2026-08-29): der Schweregrad ist im Fund längst enthalten, kam
                // auf dem Dashboard aber nie an — "3" sah dort genauso aus, egal ob dahinter drei
                // harmlose Infos oder ein kritischer Signaturwechsel standen.
                highestFindingSeverity = findingsResult?.maxByOrNull { it.severity.ordinal }?.severity,
                activeProfile = activeProfile,
                onOpenFailsafe = onOpenFailsafe,
                onOpenSensitiveAction = onOpenSensitiveAction,
                onOpenPinManagement = onOpenPinManagement,
                onOpenAppManagement = { screen = WardenScreen.AppManagement },
                onOpenSecurityScanner = { screen = WardenScreen.SecurityScanner },
                onOpenSafeguards = { screen = WardenScreen.Safeguards },
                onOpenSettings = { screen = WardenScreen.Settings },
                onOpenLog = onOpenLog,
                onLockNow = {
                    runCatching { concordBus.lockNow() }
                        .onFailure { Log.e("WardenStatus", "Jetzt-sperren fehlgeschlagen", it) }
                },
                kioskQuickTriggerVisible = kioskQuickTriggerVisible,
                kioskTriggerProfile = lockdownTriggerProfile,
                onKioskNow = onKioskNow,
                onOpenPermissionAudit = { screen = WardenScreen.PermissionAudit },
                onOpenPerformanceMonitor = { screen = WardenScreen.PerformanceMonitor },
                onOpenNetwork = { screen = WardenScreen.Network },
                onOpenSecurityScore = { screen = WardenScreen.SecurityScore },
            )
        }
        WardenScreen.AppManagement -> {
            // Architektur-Review 2026-08-24 (F-2): `listManagedApps()` fragt via
            // `getInstalledApplications(MATCH_UNINSTALLED_PACKAGES)` JEDES installierte Paket ab
            // (Warden hält QUERY_ALL_PACKAGES) und liest pro Paket den DPM-Freeze-Zustand — auf
            // einem realen Gerät ein potenziell langsamer, synchron im Composition-Body
            // ausgeführter Main-Thread-Block. Jetzt: `LaunchedEffect` + `Dispatchers.IO`, mit
            // einem Lade-Gate (`initialLoadDone`) statt eines rohen `null`-Startwerts, damit
            // "lädt noch" nie fälschlich als "Lesen fehlgeschlagen" (`loadFailed`) erscheint —
            // dieselbe Fail-Safe-Unterscheidung wie sonst in dieser Datei, nur um einen
            // dritten, sichtbaren Zustand ergänzt statt sie zu verwässern.
            var appsResult by remember { mutableStateOf<List<AppManagementInfo>?>(null) }
            var initialLoadDone by remember { mutableStateOf(false) }
            val appManagementScope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                appsResult = withContext(Dispatchers.IO) { loadManagedAppsSafely(concordBus) }
                initialLoadDone = true
            }
            if (!initialLoadDone) {
                LoadingScreen(title = stringResource(R.string.menu_app_management_title), onBack = { screen = WardenScreen.Status })
            } else {
                AppManagementScreen(
                    apps = appsResult.orEmpty(),
                    loadFailed = appsResult == null,
                    onBack = { screen = WardenScreen.Status },
                    // Vorschlag V-6 (2026-08-29): derselbe Ladevorgang wie im LaunchedEffect oben,
                    // nur erneut angestoßen — kein zweiter Ladepfad, der auseinanderlaufen könnte.
                    onRetry = {
                        appManagementScope.launch {
                            appsResult = withContext(Dispatchers.IO) { loadManagedAppsSafely(concordBus) }
                        }
                    },
                    onToggleFrozen = { packageName, frozen ->
                        appManagementScope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { concordBus.setAppFrozen(packageName, frozen) }
                            }
                            appsResult = withContext(Dispatchers.IO) { loadManagedAppsSafely(concordBus) }
                        }
                    },
                )
            }
        }
        WardenScreen.SecurityScanner -> {
            // Architektur-Review 2026-08-24 (F-2): alle drei Erstladungen liefen bisher synchron
            // im Composition-Body (Scanner-Status/Funde-Envelope/Integritäts-Check) — jetzt hinter
            // demselben Lade-Gate-Muster wie [WardenScreen.AppManagement]. `scanInProgress`
            // (bislang nur für den "Jetzt scannen"-Button) übernimmt zusätzlich die
            // Nach-Mutation-Neuladungen (Schalter/"Vertrauen") — dieselbe Fail-Safe-Haltung wie
            // beim Sofort-Scan: der `Dispatchers.Default`-Fehlgriff dort (CPU-Pool statt I/O-Pool
            // für DPM-/Envelope-Zugriffe) ist mitkorrigiert.
            var enabled by remember { mutableStateOf<Boolean?>(null) }
            var findingsResult by remember { mutableStateOf<List<SuspiciousAppFindingInfo>?>(null) }
            var integrityStatus by remember { mutableStateOf<DeviceIntegrityStatus?>(null) }
            var initialLoadDone by remember { mutableStateOf(false) }
            var scanInProgress by remember { mutableStateOf(false) }
            val scanScope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                enabled = withContext(Dispatchers.IO) { loadScannerEnabledSafely(concordBus) }
                findingsResult = withContext(Dispatchers.IO) { loadFindingsSafely(concordBus) }
                integrityStatus = withContext(Dispatchers.IO) { loadDeviceIntegrityStatusSafely(concordBus) }
                initialLoadDone = true
            }
            if (!initialLoadDone) {
                LoadingScreen(title = stringResource(R.string.menu_security_scanner_title), onBack = { screen = WardenScreen.Status })
            } else {
                SecurityScannerScreen(
                    // Vorschlag V-6 (2026-08-29): wiederholt genau die drei Lesevorgänge des
                    // LaunchedEffect oben, ohne einen neuen Scan auszulösen.
                    onRetry = {
                        scanScope.launch {
                            enabled = withContext(Dispatchers.IO) { loadScannerEnabledSafely(concordBus) }
                            findingsResult = withContext(Dispatchers.IO) { loadFindingsSafely(concordBus) }
                            integrityStatus = withContext(Dispatchers.IO) { loadDeviceIntegrityStatusSafely(concordBus) }
                        }
                    },
                    scannerEnabled = enabled,
                    findings = findingsResult.orEmpty(),
                    findingsLoadFailed = findingsResult == null,
                    deviceIntegrityStatus = integrityStatus,
                    scanInProgress = scanInProgress,
                    onBack = { screen = WardenScreen.Status },
                    onToggleScannerEnabled = { requested ->
                        scanScope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { concordBus.setAutoFreezeScannerEnabled(requested) }
                            }
                            enabled = withContext(Dispatchers.IO) { loadScannerEnabledSafely(concordBus) }
                            findingsResult = withContext(Dispatchers.IO) { loadFindingsSafely(concordBus) }
                        }
                    },
                    onTrust = { packageName ->
                        scanScope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { concordBus.trustSuspiciousApp(packageName) }
                            }
                            findingsResult = withContext(Dispatchers.IO) { loadFindingsSafely(concordBus) }
                        }
                    },
                    onRunImmediateScan = {
                        if (!scanInProgress) {
                            scanScope.launch {
                                scanInProgress = true
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { concordBus.runImmediateSuspiciousAppScan() }
                                    }
                                    findingsResult = result
                                        .onFailure { Log.e("WardenStatus", "Sofort-Scan fehlgeschlagen", it) }
                                        .getOrNull() ?: findingsResult
                                    enabled = withContext(Dispatchers.IO) { loadScannerEnabledSafely(concordBus) }
                                    integrityStatus = withContext(Dispatchers.IO) { loadDeviceIntegrityStatusSafely(concordBus) }
                                } finally {
                                    scanInProgress = false
                                }
                            }
                        }
                    },
                )
            }
        }
        WardenScreen.Safeguards -> {
            val appContext = LocalContext.current.applicationContext
            // Befund Q-2 (2026-08-28, aus der Code-/Sicherheitsanalyse): vorher las jeder der 33
            // Schalter seinen Zustand einzeln und **synchron im Kompositions-Body** — 33
            // DPM-Binder-Aufrufe plus 33 vollständige Log-Schreibzyklen auf dem Main-Thread, bei
            // jedem Bildschirmaufbau und nach jedem `catalogGeneration++` erneut. Jetzt derselbe
            // Aufbau wie in den übrigen Screens dieser Datei: ein `LaunchedEffect` auf
            // `Dispatchers.IO`, ein einziger gebündelter Bus-Aufruf
            // (`ConcordBus.safeguardStates`), und ein Lade-Gate (`snapshot == null`), damit
            // "lädt noch" nie fälschlich als "Lesen fehlgeschlagen" erscheint.
            val safeguardScope = rememberCoroutineScope()
            var catalogGeneration by remember { mutableIntStateOf(0) }
            var snapshot by remember { mutableStateOf<SafeguardsSnapshot?>(null) }
            var profileApplyWarning by remember { mutableStateOf<String?>(null) }
            // "LockMode/Threat-Protection-Ausbau" (2026-08-25) — reine lokale SharedPreferences-
            // Werte (kein DPM-Zugriff wie die übrigen Toggles oben), deshalb ohne den
            // Snapshot/ConcordBus direkt hier geladen/geschrieben.
            var emergencyDrillConfirmed by remember { mutableStateOf(WardenLockTaskDrillStorage.isConfirmed(appContext)) }
            var autoEngageOnCriticalThreat by remember { mutableStateOf(WardenLockTaskAutoEngageStore.isEnabled(appContext)) }
            // "Lockdown-Auslöse-Profil" (2026-08-27) — dieselbe Store-→-Compose-State-→-Write-
            // through-Verkabelung wie autoEngageOnCriticalThreat direkt darüber.
            var lockdownTriggerProfile by remember { mutableStateOf(LockdownTriggerProfileStore.load(appContext)) }
            LaunchedEffect(catalogGeneration) {
                snapshot = withContext(Dispatchers.IO) { loadSafeguardsSnapshotSafely(concordBus, appContext) }
            }
            val loaded = snapshot
            if (loaded == null) {
                LoadingScreen(title = stringResource(R.string.menu_safeguards_title), onBack = { screen = WardenScreen.Status })
            } else key(catalogGeneration) {
                /** Ein Schalter aus dem bereits geladenen Snapshot — s. Kommentar oben. Das
                 * Umschalten selbst läuft auf IO und stößt über `catalogGeneration++` genau einen
                 * neuen Sammel-Lesevorgang an, statt pro Schalter noch einmal einzeln zu lesen. */
                fun toggle(safeguardId: String) = SafeguardToggleState(
                    locked = loaded.safeguardStates[safeguardId],
                    onToggle = { requested ->
                        safeguardScope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    if (requested) {
                                        concordBus.applySafeguard(safeguardId)
                                    } else {
                                        concordBus.revertSafeguard(safeguardId)
                                    }
                                }.onFailure {
                                    Log.e("WardenStatus", "Safeguard-Schalter ($safeguardId) fehlgeschlagen", it)
                                }
                            }
                            catalogGeneration++
                        }
                    },
                )
                /** "USB automatisch sperren bei Bildschirmsperre" (2026-08-22) — dasselbe
                 * Muster wie [toggle], aber gegen [ConcordBus.isUsbAutoLockEnabled]/
                 * [ConcordBus.setUsbAutoLockEnabled] statt gegen die `Safeguard`-Registry: das
                 * schaltet keine Safeguard-`apply()`/`revert()`, sondern nur eine lokale
                 * Präferenz, die [de.ble1st.warden.usb.UsbAutoLockController] periodisch ausliest. */
                val usbAutoLockToggle = SafeguardToggleState(
                    locked = loaded.usbAutoLockEnabled,
                    onToggle = { requested ->
                        safeguardScope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { concordBus.setUsbAutoLockEnabled(requested) }
                                    .onFailure { Log.e("WardenStatus", "USB-Auto-Lock-Schalter fehlgeschlagen", it) }
                            }
                            catalogGeneration++
                        }
                    },
                )
                SafeguardsScreen(
                    // Vorschlag U-1 (2026-08-29): statt 33 einzeln benannter Parameter genau ein
                    // Zugriffspunkt. Die Zuordnung ID -> Zeile/Text/Gruppe steht jetzt in
                    // SafeguardUiCatalog; ein neuer Safeguard braucht hier keine Änderung mehr.
                    // Der einzige Sonderfall ist USB-Auto-Lock: keine Registry-Safeguard, sondern
                    // eine lokale Präferenz — s. SafeguardUiCatalog.USB_AUTO_LOCK_ID.
                    toggleFor = { id ->
                        if (id == SafeguardUiCatalog.USB_AUTO_LOCK_ID) usbAutoLockToggle else toggle(id)
                    },
                    factoryResetProtectionAccounts = loaded.factoryResetProtectionAccounts,
                    factoryResetProtectionAgentAvailable = loaded.factoryResetProtectionAgentAvailable,
                    onSaveFactoryResetProtectionAccounts = { raw ->
                        // Speichern schreibt Storage *und* DPM — beides auf IO, das Ergebnis holt
                        // der Sammel-Lesevorgang über catalogGeneration++ zurück (2026-08-28,
                        // Befund Q-2); vorher lief der ganze Block synchron in der Komposition.
                        safeguardScope.launch {
                            withContext(Dispatchers.IO) {
                                when (val decision = FactoryResetProtectionAccounts.evaluateRaw(raw)) {
                                    is FactoryResetProtectionDecision.Valid -> {
                                        WardenFactoryResetProtectionStorage.save(appContext, decision.accounts)
                                        runCatching {
                                            concordBus.applySafeguard(FactoryResetProtectionSafeguard.ID)
                                            concordBus.applySafeguard(UserRestrictionSafeguard.MODIFY_ACCOUNTS_DISABLED_ID)
                                        }.onFailure { Log.e("WardenStatus", "FRP-Konten-Abgleich fehlgeschlagen", it) }
                                        if (!FactoryResetProtectionSafeguard(appContext).isFrpAgentAvailable()) {
                                            Log.w("WardenStatus", "FRP aktiviert, aber Google-Play-Dienste nicht gefunden — Policy wird vermutlich nicht durchgesetzt")
                                        }
                                    }
                                    FactoryResetProtectionDecision.Empty -> {
                                        WardenFactoryResetProtectionStorage.save(appContext, emptyList())
                                        runCatching {
                                            concordBus.revertSafeguard(FactoryResetProtectionSafeguard.ID)
                                        }.onFailure { Log.e("WardenStatus", "FRP-Konten-Abgleich fehlgeschlagen", it) }
                                    }
                                    FactoryResetProtectionDecision.TooMany ->
                                        Log.w("WardenStatus", "FRP: höchstens ${FactoryResetProtectionAccounts.MAX_ACCOUNTS} Konten")
                                    FactoryResetProtectionDecision.TooLong ->
                                        Log.w("WardenStatus", "FRP: Konto zu lang (max ${FactoryResetProtectionAccounts.MAX_ACCOUNT_LENGTH})")
                                }
                            }
                            catalogGeneration++
                        }
                    },
                    lockdownModeActive = loaded.lockdownModeActive,
                    profileApplyWarning = profileApplyWarning,
                    onApplyProfile = { profile: WardenProfile ->
                        // Ein Profil-Apply schaltet den gesamten Katalog um — der teuerste
                        // DPM-Block dieses Bildschirms und deshalb erst recht nicht auf dem
                        // Main-Thread (2026-08-28, Befund Q-2).
                        safeguardScope.launch {
                            profileApplyWarning = withContext(Dispatchers.IO) {
                                runCatching { concordBus.applyProfile(profile) }
                                    .fold(
                                        onSuccess = { result ->
                                            when {
                                                result.failed.isNotEmpty() ->
                                                    "Profil ${profile.label}: fehlgeschlagen für ${result.failed.joinToString()}"
                                                result.skipped.isNotEmpty() ->
                                                    "Profil ${profile.label}: ohne FRP-Konten angewendet — " +
                                                        "Kontosperre nach Reset bleibt aus, bis Konten hinterlegt sind."
                                                else -> null
                                            }
                                        },
                                        onFailure = {
                                            Log.e("WardenStatus", "Profil-Anwendung fehlgeschlagen", it)
                                            "Profil ${profile.label}: Anwendung fehlgeschlagen."
                                        },
                                    )
                            }
                            catalogGeneration++
                        }
                    },
                    emergencyDrillConfirmed = emergencyDrillConfirmed,
                    emergencyDrillConfirmedAtText = WardenLockTaskDrillStorage.confirmedAtMillis(appContext)
                        ?.let { millis -> DateFormat.getDateTimeInstance().format(Date(millis)) },
                    onConfirmEmergencyDrill = {
                        WardenLockTaskDrillStorage.confirm(appContext)
                        emergencyDrillConfirmed = true
                    },
                    onRevokeEmergencyDrill = {
                        WardenLockTaskDrillStorage.revoke(appContext)
                        emergencyDrillConfirmed = false
                        // Ein widerrufener Drill macht den Auto-Engage-Opt-in wirkungslos (Gate
                        // verweigert ohnehin) — hier zusätzlich sichtbar mit zurückgesetzt, statt
                        // eines Schalters, der "an" zeigt, aber nichts mehr bewirkt.
                        WardenLockTaskAutoEngageStore.setEnabled(appContext, false)
                        autoEngageOnCriticalThreat = false
                    },
                    autoEngageOnCriticalThreat = autoEngageOnCriticalThreat,
                    onAutoEngageOnCriticalThreatChange = { requested ->
                        WardenLockTaskAutoEngageStore.setEnabled(appContext, requested)
                        autoEngageOnCriticalThreat = requested
                    },
                    sentinelLockTaskAuthorized = loaded.sentinelLockTaskAuthorized,
                    sentinelInstallStatus = loaded.sentinelInstallStatus,
                    sentinelPinConfigured = loaded.sentinelPinConfigured,
                    activeProfile = loaded.activeProfile,
                    onInstallSentinel = {
                        // Nur der synchrone Teil (Session erzeugt/committet) ist hier sichtbar —
                        // das eigentliche Ergebnis kommt asynchron über
                        // SentinelInstallResultReceiver (Log-Eintrag), s. dessen Klassendoc. Der
                        // Status hier bleibt bis zum nächsten "Status prüfen"/Bildschirmwechsel
                        // auf dem alten Stand — dieselbe "informativ, nicht perfekt live"-Haltung
                        // wie bei sentinelLockTaskAuthorized oben.
                        runCatching { SentinelSilentInstaller(appContext).install() }
                            .onFailure { Log.e("WardenStatus", "Sentinel-Silent-Install nicht auslösbar", it) }
                    },
                    onRefreshSentinelInstallStatus = { catalogGeneration++ },
                    lockdownTriggerProfile = lockdownTriggerProfile,
                    onLockdownTriggerProfileChange = { selected ->
                        LockdownTriggerProfileStore.save(appContext, selected)
                        lockdownTriggerProfile = selected
                    },
                    onBack = { screen = WardenScreen.Status },
                )
            }
        }
        WardenScreen.Settings -> {
            SettingsScreen(
                accent = accent,
                onAccentChange = onAccentChange,
                lockScreenText = lockScreenText,
                onLockScreenTextChange = onLockScreenTextChange,
                organizationName = organizationName,
                onOrganizationNameChange = onOrganizationNameChange,
                supportMessage = supportMessage,
                onSupportMessageChange = onSupportMessageChange,
                autoRebootThresholdHours = autoRebootThresholdHours,
                onAutoRebootThresholdHoursChange = onAutoRebootThresholdHoursChange,
                failedAttemptsRebootThreshold = failedAttemptsRebootThreshold,
                secureLockScreenConfigured = secureLockScreenConfigured,
                onFailedAttemptsRebootThresholdChange = onFailedAttemptsRebootThresholdChange,
                simChangeReaction = simChangeReaction,
                onSimChangeReactionChange = onSimChangeReactionChange,
                cellSecurityReaction = cellSecurityReaction,
                onCellSecurityReactionChange = onCellSecurityReactionChange,
                autoProfileConfig = autoProfileConfig,
                onAutoProfileConfigChange = onAutoProfileConfigChange,
                onBack = { screen = WardenScreen.Status },
            )
        }
        WardenScreen.PermissionAudit -> {
            val appContext = LocalContext.current.applicationContext
            val scanScope = rememberCoroutineScope()
            var findings by remember { mutableStateOf<List<PermissionAuditInfo>?>(null) }
            var scanInProgress by remember { mutableStateOf(false) }
            // Feature 3 "Permission Auto-Block" (2026-08-29): welche Pakete aktuell gesperrte
            // Rechte haben, wird separat von RevokedPermissionStore nachgeschlagen statt aus
            // findings abgeleitet — ein Scan-Ergebnis kennt den Sperrzustand nicht, das ist reiner
            // ConcordBus-Zustand. Bei jedem (Neu-)Scan neu ermittelt, damit ein manuelles
            // Sperren/Wiederherstellen sofort in der Liste sichtbar wird.
            var revokedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }

            fun refreshRevokedState(candidates: List<PermissionAuditInfo>) {
                scanScope.launch {
                    revokedPackages = withContext(Dispatchers.IO) {
                        candidates.filter { runCatching { concordBus.hasRevokedPermissions(it.packageName) }.getOrDefault(false) }
                            .map { it.packageName }
                            .toSet()
                    }
                }
            }

            PermissionAuditScreen(
                findings = findings,
                scanInProgress = scanInProgress,
                revokedPackages = revokedPackages,
                onBack = { screen = WardenScreen.Status },
                onScan = {
                    if (!scanInProgress) {
                        scanScope.launch {
                            scanInProgress = true
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { PermissionAuditScanner(appContext).scan() }
                                        .onFailure { Log.e("WardenStatus", "Permission-Audit fehlgeschlagen", it) }
                                        .getOrNull()
                                }
                                findings = result
                                if (result != null) refreshRevokedState(result)
                            } finally {
                                scanInProgress = false
                            }
                        }
                    }
                },
                onRevoke = { packageName ->
                    scanScope.launch {
                        withContext(Dispatchers.IO) { runCatching { concordBus.revokeDangerousPermissions(packageName) } }
                        findings?.let { refreshRevokedState(it) }
                    }
                },
                onRestore = { packageName ->
                    scanScope.launch {
                        withContext(Dispatchers.IO) { runCatching { concordBus.restoreDangerousPermissions(packageName) } }
                        findings?.let { refreshRevokedState(it) }
                    }
                },
            )
        }
        WardenScreen.PerformanceMonitor -> {
            val appContext = LocalContext.current.applicationContext
            val perfScope = rememberCoroutineScope()
            var memory by remember { mutableStateOf<DeviceMemorySnapshot?>(null) }
            var battery by remember { mutableStateOf<BatterySnapshot?>(null) }
            var drainPercentPerHour by remember { mutableStateOf<Double?>(null) }
            var usageAccessGranted by remember { mutableStateOf(false) }
            var usageFindings by remember { mutableStateOf<List<AppUsageInfo>?>(null) }
            var suspiciousPackageNames by remember { mutableStateOf<Set<String>>(emptySet()) }
            var appLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

            fun refresh() {
                perfScope.launch {
                    // Dieselbe "im IO-Dispatcher berechnen, erst danach im Composable-Aufrufer
                    // zuweisen"-Struktur wie überall sonst in dieser Datei (z. B.
                    // WardenScreen.SecurityScanner oben) — ein Snapshot-Bündel statt einzelner
                    // Direktzuweisungen innerhalb von withContext, damit alle sieben Werte aus
                    // demselben, konsistenten Lesezeitpunkt stammen und die State-Schreibzugriffe
                    // selbst auf dem ursprünglichen (Main-)Dispatcher passieren.
                    val snapshot = withContext(Dispatchers.IO) {
                        val usageReader = AppUsageReader(appContext)
                        PerformanceSnapshot(
                            memory = runCatching { DeviceMemoryReader(appContext).read() }.getOrNull(),
                            battery = runCatching { BatteryStatusReader(appContext).read() }.getOrNull(),
                            drainPercentPerHour = runCatching {
                                BatteryDrainDecision.percentPerHour(BatteryHistoryStore(appContext).recentUnchargedSamples())
                            }.getOrNull(),
                            usageAccessGranted = runCatching { usageReader.hasAccess() }.getOrDefault(false),
                            usageFindings = runCatching { usageReader.recentForegroundUsage() }.getOrNull(),
                            suspiciousPackageNames = loadFindingsSafely(concordBus)?.map { it.packageName }?.toSet().orEmpty(),
                            appLabels = runCatching {
                                InstalledAppLister(appContext).listInstalledApps().associate { it.packageName to it.label }
                            }.getOrDefault(emptyMap()),
                        )
                    }
                    memory = snapshot.memory
                    battery = snapshot.battery
                    drainPercentPerHour = snapshot.drainPercentPerHour
                    usageAccessGranted = snapshot.usageAccessGranted
                    usageFindings = snapshot.usageFindings
                    suspiciousPackageNames = snapshot.suspiciousPackageNames
                    appLabels = snapshot.appLabels
                }
            }
            LaunchedEffect(Unit) { refresh() }

            PerformanceMonitorScreen(
                memory = memory,
                battery = battery,
                batteryDrainPercentPerHour = drainPercentPerHour,
                usageAccessGranted = usageAccessGranted,
                usageFindings = usageFindings,
                suspiciousPackageNames = suspiciousPackageNames,
                appLabels = appLabels,
                onBack = { screen = WardenScreen.Status },
                onRequestUsageAccess = {
                    runCatching { appContext.startActivity(AppUsageReader(appContext).usageAccessSettingsIntent()) }
                        .onFailure { Log.e("WardenStatus", "Nutzungsdatenzugriff-Einstellungen nicht erreichbar", it) }
                },
                onRefresh = { refresh() },
            )
        }
        is WardenScreen.Network -> {
            val appContext = LocalContext.current.applicationContext
            val netLockdownController = remember { NetLockdownController(appContext) }
            val networkFirewallPolicyController = remember { NetworkFirewallPolicyController(appContext) }
            val apps by remember { mutableStateOf(networkFirewallPolicyController.listApps()) }
            // 2026-08-29 (Reaktivierungs-Fix): `netLockdownController.isActive()` liest live über
            // DPM/`getAlwaysOnVpnPackage` — kein Compose-`State`, das `derivedStateOf` (Commit
            // e5dbe70) beobachten könnte. Ohne einen selbst gehaltenen Snapshot-State blieb der
            // Schalter dauerhaft auf dem beim ersten Rendern gelesenen Wert stehen, egal was
            // `onToggleLockdown` danach auslöste — dasselbe "UI reagiert nicht"-Symptom, das
            // e5dbe70 eigentlich beheben sollte. Stattdessen: eigener `mutableStateOf`, einmal per
            // `LaunchedEffect` initial befüllt und nach jedem Arm/Disarm explizit mit dem
            // tatsächlichen `isActive()`-Ist-Zustand aktualisiert (nie optimistisch auf den
            // gewünschten Zielwert gesetzt) — dieselbe "Ist-Zustand ist immer die Wahrheit"-Regel
            // wie beim Safeguard-Registry (s. CLAUDE.md).
            var lockdownActive by remember { mutableStateOf<Boolean?>(null) }
            val networkScope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                lockdownActive = withContext(Dispatchers.IO) { netLockdownController.isActive() }
            }

            // 2026-08-29 (Verdrahtungs-Lücke gefunden und geschlossen): die Blocklisten-UI rief
            // vorher nirgendwo `DomainBlocklistStore` auf — `userBlocklistDomains`/
            // `defaultBlocklistSize` standen fest auf leer/0 und `onAddDomain`/`onRemoveDomain`
            // waren No-ops. Der Speicher und die Rust-Engine-Verkabelung ([WardenVpnService
            // .startTunnel]) waren dabei die ganze Zeit vollständig funktionsfähig — nur der
            // Bildschirm hing an nichts. Dieselbe "IO-Dispatcher + Neuladen nach jeder Änderung"-
            // Struktur wie bei [apps]/[lockdownActive] oben.
            val blocklistStore = remember { DomainBlocklistStore(DomainBlocklistStore.buildEnvelopeFile(appContext)) }
            var userDomains by remember { mutableStateOf<Set<String>>(emptySet()) }
            LaunchedEffect(Unit) {
                userDomains = withContext(Dispatchers.IO) { blocklistStore.loadUserDomains() }
            }

            // ChildVPN (2026-08-31, docs/design-barbican-prozess-childvpn.md) — identisches
            // "IO-Dispatcher + Neuladen nach jeder Änderung"-Muster wie die Blockliste oben.
            // `childVpnConfigStore` läuft in diesem (Haupt-)Prozess nur als reine Datei-Persistenz;
            // die eigentliche Rust-Engine-Aktivierung passiert ausschließlich in [WardenVpnService]
            // (`:barbican`-Prozess), angestoßen über [NetLockdownController.resyncChildVpn] — s.
            // dortiges Klassendoc "ChildVPN" für die Begründung.
            val childVpnConfigStore = remember { ChildVpnConfigStore(ChildVpnConfigStore.buildEnvelopeFile(appContext)) }
            var childVpnEndpoint by remember { mutableStateOf<String?>(null) }
            var childVpnError by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) { runCatching { childVpnConfigStore.load() } }.fold(
                    onSuccess = { config -> childVpnEndpoint = config?.let { "${it.endpointHost}:${it.endpointPort}" } },
                    onFailure = { e -> childVpnError = e.message ?: e.toString() },
                )
            }

            NetworkScreen(
                lockdownActive = lockdownActive,
                onToggleLockdown = { enabled ->
                    networkScope.launch {
                        withContext(Dispatchers.IO) {
                            if (enabled) netLockdownController.arm() else netLockdownController.disarm()
                        }
                        lockdownActive = withContext(Dispatchers.IO) { netLockdownController.isActive() }
                    }
                },
                apps = apps,
                appsLoadFailed = false,
                modeFor = { pkg -> networkFirewallPolicyController.modeFor(pkg) },
                onSetMode = { pkg, mode -> networkFirewallPolicyController.setMode(pkg, mode) },
                userBlocklistDomains = userDomains,
                defaultBlocklistSize = DomainBlocklistStore.DEFAULT_TRACKER_DOMAINS.size,
                onAddDomain = { domain ->
                    networkScope.launch {
                        withContext(Dispatchers.IO) {
                            blocklistStore.addDomain(domain)
                            netLockdownController.resyncBlocklist()
                        }
                        userDomains = withContext(Dispatchers.IO) { blocklistStore.loadUserDomains() }
                    }
                },
                onRemoveDomain = { domain ->
                    networkScope.launch {
                        withContext(Dispatchers.IO) {
                            blocklistStore.removeDomain(domain)
                            netLockdownController.resyncBlocklist()
                        }
                        userDomains = withContext(Dispatchers.IO) { blocklistStore.loadUserDomains() }
                    }
                },
                childVpnConfiguredEndpoint = childVpnEndpoint,
                onApplyChildVpnConfig = { text ->
                    networkScope.launch {
                        ChildVpnConfigParser.parse(text).fold(
                            onSuccess = { config ->
                                withContext(Dispatchers.IO) {
                                    childVpnConfigStore.save(config)
                                    netLockdownController.resyncChildVpn()
                                }
                                childVpnEndpoint = "${config.endpointHost}:${config.endpointPort}"
                                childVpnError = null
                            },
                            onFailure = { e -> childVpnError = describeChildVpnParseError(e) },
                        )
                    }
                },
                onRemoveChildVpnConfig = {
                    networkScope.launch {
                        withContext(Dispatchers.IO) {
                            childVpnConfigStore.clear()
                            netLockdownController.resyncChildVpn()
                        }
                        childVpnEndpoint = null
                        childVpnError = null
                    }
                },
                childVpnError = childVpnError,
                onBack = { screen = WardenScreen.Status }
            )
        }
        WardenScreen.SecurityScore -> {
            val appContext = LocalContext.current.applicationContext
            val scoreScope = rememberCoroutineScope()
            var breakdown by remember { mutableStateOf<SecurityScoreBreakdown?>(null) }
            var calculationFailed by remember { mutableStateOf(false) }
            var calculationInProgress by remember { mutableStateOf(false) }
            var history by remember { mutableStateOf<List<SecurityScoreHistoryStore.HistoryEntry>>(emptyList()) }
            // Verlauf wird unabhängig von einer frischen Berechnung geladen — beim Öffnen des
            // Bildschirms zeigt er sofort, was frühere Sitzungen bereits aufgezeichnet haben.
            LaunchedEffect(Unit) {
                history = withContext(Dispatchers.IO) {
                    runCatching { SecurityScoreHistoryStore(appContext).entriesWithinWindow() }.getOrDefault(emptyList())
                }
            }
            SecurityScoreScreen(
                breakdown = breakdown,
                calculationFailed = calculationFailed,
                calculationInProgress = calculationInProgress,
                history = history,
                onBack = { screen = WardenScreen.Status },
                onCalculate = {
                    if (!calculationInProgress) {
                        scoreScope.launch {
                            calculationInProgress = true
                            calculationFailed = false
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { SecurityScoreCalculator(appContext, concordBus).calculate() }
                                        .onFailure { Log.e("WardenStatus", "Sicherheits-Score-Berechnung fehlgeschlagen", it) }
                                        .getOrNull()
                                }
                                breakdown = result
                                calculationFailed = result == null
                                if (result != null) {
                                    // Nur bei echtem Erfolg aufzeichnen — ein fehlgeschlagener
                                    // Versuch (z. B. kein Device Owner mehr aktiv) ist kein
                                    // gültiger Score-Datenpunkt.
                                    val historyStore = SecurityScoreHistoryStore(appContext)
                                    withContext(Dispatchers.IO) { historyStore.record(result) }
                                    history = withContext(Dispatchers.IO) { historyStore.entriesWithinWindow() }
                                }
                            } finally {
                                calculationInProgress = false
                            }
                        }
                    }
                },
            )
        }
    }
}

/** Architektur-Review 2026-08-24 (F-2): Voll-Bildschirm-Lade-Zustand für Unterbildschirme mit
 * potenziell langsamer Erstladung ([WardenScreen.AppManagement]/[WardenScreen.SecurityScanner]).
 * Bewusst als eigener Bildschirm statt als `null`-Startwert direkt im echten Screen: die
 * `T?`-Konvention dieser Datei ([loadManagedAppsSafely] & Co.) bedeutet überall sonst "Lesen
 * fehlgeschlagen" — ein `null`-Startwert während des Ladens würde genau diese Unterscheidung für
 * einen Frame lang verwässern (z. B. "Scanner-Status konnte nicht gelesen werden" statt "lädt
 * noch"). Mit eigenem Back-Ziel, damit die Zurück-Geste (s. [BackHandler] oben) auch während des
 * Ladens funktioniert. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoadingScreen(title: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

/**
 * Ein vollständiger Lesezyklus des Safeguards-Screens (2026-08-28, Befund Q-2) — dieselbe
 * "ein Snapshot statt N zeitversetzter Einzelwerte"-Idee wie [PerformanceSnapshot], hier
 * zusätzlich motiviert durch die Kosten: [safeguardStates] ersetzt 33 einzeln autorisierte
 * Bus-Aufrufe durch einen.
 *
 * `null` in [safeguardStates] heißt weiterhin "dieser eine Zustand ist nicht lesbar" und nicht
 * "aus"; fehlt eine ID ganz (sie ist nicht registriert), liefert der Zugriff ebenfalls `null` —
 * beides rendert die UI als "nicht lesbar", nie als "in Ordnung".
 */
private data class SafeguardsSnapshot(
    val safeguardStates: Map<String, Boolean?>,
    val usbAutoLockEnabled: Boolean?,
    val lockdownModeActive: Boolean?,
    val sentinelLockTaskAuthorized: Boolean?,
    val sentinelInstallStatus: SentinelInstallStatus,
    /** `null` = Sentinel hat sich noch nie gemeldet, *nicht* "keine PIN" — s.
     * [SentinelPinStateStore] (Vorschlag U-8). */
    val sentinelPinConfigured: Boolean?,
    /** Zuletzt angewandtes Profil, manuell oder automatisch; `null` = noch nie eines angewandt
     * (Vorschlag V-1, 2026-08-29). */
    val activeProfile: WardenProfile?,
    val factoryResetProtectionAccounts: String,
    val factoryResetProtectionAgentAvailable: Boolean,
)

/**
 * Lädt [SafeguardsSnapshot] — **muss auf [Dispatchers.IO] laufen** (DPM-Binder-Aufrufe für jeden
 * Katalogeintrag plus PackageManager). Die Liste der IDs kommt aus
 * [ConcordBus.listSafeguards]: so deckt der Sammel-Lesevorgang automatisch jeden registrierten
 * Safeguard ab und kann nicht gegenüber dem Katalog veralten, wie es eine hier gepflegte
 * Aufzählung könnte.
 */
private fun loadSafeguardsSnapshotSafely(bus: ConcordBus, context: Context): SafeguardsSnapshot {
    val states = runCatching { bus.safeguardStates(bus.listSafeguards()) }
        .onFailure { Log.e("WardenStatus", "Safeguard-Zustände nicht ladbar", it) }
        .getOrDefault(emptyMap())
    return SafeguardsSnapshot(
        safeguardStates = states,
        usbAutoLockEnabled = loadUsbAutoLockEnabledSafely(bus),
        lockdownModeActive = loadLockdownModeActiveSafely(bus),
        sentinelLockTaskAuthorized = loadSentinelLockTaskAuthorizedSafely(context),
        sentinelInstallStatus = SentinelInstallStatusReader(context).currentStatus(),
        sentinelPinConfigured = SentinelPinStateStore.pinConfigured(context),
        activeProfile = AutoProfileStorage.loadLastEffective(context),
        factoryResetProtectionAccounts = WardenFactoryResetProtectionStorage.load(context).joinToString("\n"),
        factoryResetProtectionAgentAvailable = FactoryResetProtectionSafeguard(context).isFrpAgentAvailable(),
    )
}


private fun loadUsbAutoLockEnabledSafely(bus: ConcordBus): Boolean? =
    runCatching { bus.isUsbAutoLockEnabled() }
        .onFailure { Log.e("WardenStatus", "USB-Auto-Lock-Status nicht ladbar", it) }
        .getOrNull()

/** Alle Lade-Helfer fangen [SecurityException] ab (s. [ConcordBus.authorize]-Doc: eine
 * abgelehnte Autorisierung wirft, statt still einen leeren/falschen Wert zu liefern) — hier auf
 * UI-Ebene bewusst zu einem sicheren Wert degradiert, weil ein abgelehnter Lesezugriff (z. B.
 * Rate-Limit, oder — der real gefundene Fall, 2026-08-21 — ein fehlender Device Owner, wodurch
 * [de.ble1st.warden.appmanagement.AppFreezeManager.isFrozen]/`isApplicationHidden` für jede Zeile
 * wirft) die restliche Statusanzeige nicht mit einem Absturz begraben soll.
 *
 * [loadManagedAppsSafely]/[loadFindingsSafely]/[loadScannerEnabledSafely]
 * liefern bewusst `T?` statt direkt `T`: `null` markiert "Lesen fehlgeschlagen", ein
 * leerer/negativer Wert bleibt "wirklich so" — sonst sieht ein fehlgeschlagener Scan/Safeguard-Read
 * für den Nutzer identisch aus wie "alles in Ordnung".
 */
private fun loadManagedAppsSafely(bus: ConcordBus): List<AppManagementInfo>? =
    runCatching { bus.listManagedApps() }
        .onFailure { Log.e("WardenStatus", "App-Liste nicht ladbar (kein Device Owner mehr?)", it) }
        .getOrNull()

private fun loadScannerEnabledSafely(bus: ConcordBus): Boolean? =
    runCatching { bus.isAutoFreezeScannerEnabled() }
        .onFailure { Log.e("WardenStatus", "Scanner-Status nicht ladbar", it) }
        .getOrNull()

private fun loadFindingsSafely(bus: ConcordBus): List<SuspiciousAppFindingInfo>? =
    runCatching { bus.listSuspiciousAppFindings() }
        .onFailure { Log.e("WardenStatus", "Funde-Liste nicht ladbar (kein Device Owner mehr?)", it) }
        .getOrNull()

private fun loadDeviceIntegrityStatusSafely(bus: ConcordBus): DeviceIntegrityStatus? =
    runCatching { bus.deviceIntegrityStatus() }
        .onFailure { Log.e("WardenStatus", "Geräte-Integritätsstatus nicht ladbar", it) }
        .getOrNull()

/** "Arbeite langsam am Lockdownmodus" (2026-08-22), dritter Schritt — reiner Lesepfad, s.
 * [ConcordBus.isLockdownModeActive]-Doc: kein Schalter, nur Statusanzeige. */
/** Bündelt einen [WardenScreen.PerformanceMonitor]-Lesezyklus (s. dortiger Kommentar) — ein
 * einzelner konsistenter Snapshot statt sieben unabhängig zeitversetzter Werte. */
private data class PerformanceSnapshot(
    val memory: DeviceMemorySnapshot?,
    val battery: BatterySnapshot?,
    val drainPercentPerHour: Double?,
    val usageAccessGranted: Boolean,
    val usageFindings: List<AppUsageInfo>?,
    val suspiciousPackageNames: Set<String>,
    val appLabels: Map<String, String>,
)

private fun loadLockdownModeActiveSafely(bus: ConcordBus): Boolean? =
    runCatching { bus.isLockdownModeActive() }
        .onFailure { Log.e("WardenStatus", "Lockdown-Modus-Status nicht ladbar", it) }
        .getOrNull()

/** Seit "Sentinel: eigenständige Kiosk-PIN-App": Warden selbst ist nie mehr das Lock-Task-Paket
 * (`ActivityManager.lockTaskModeState` auf Wardens eigenem Prozess wäre seitdem strukturell immer
 * `NONE`, deshalb kein `ActivityManager`-Aufruf mehr hier) — die einzig noch aussagekräftige, von
 * Warden aus beobachtbare Annäherung ist, ob Sentinels Paket aktuell für Lock-Task autorisiert ist
 * ([WardenLockTaskAuthorizer.isActive]), nicht ob Sentinel *gerade tatsächlich* im Kiosk-Zustand
 * ist (das weiß nur Sentinels eigener, fremder Prozess). */
private fun loadSentinelLockTaskAuthorizedSafely(context: Context): Boolean? =
    runCatching { WardenLockTaskAuthorizer(context).isActive() }
        .onFailure { Log.e("WardenStatus", "Sentinel-Lock-Task-Autorisierung nicht ladbar", it) }
        .getOrNull()

/** "Lockdown-Auslöse-Profil" (2026-08-27) — Statuszeile für den Dashboard-Button "Kiosk jetzt",
 * spiegelt `de.ble1st.warden.presence.SensitiveActionActivity`s private `describeOutcome` (nur für
 * `LOCKDOWN_TASK_ENGAGE` aufgerufen, deshalb ohne dessen `WIPE_DATA`-Sonderfall).
 *
 * **Befund Q-5 (2026-08-29):** nimmt jetzt [SensitiveActionOutcome] statt der reinen
 * Vorab-Entscheidung entgegen, s. `SensitiveActionActivity.describeOutcome`-KDoc für die volle
 * Begründung — ein real fehlgeschlagenes `SentinelLockdownEngager.engage()` zeigte hier vorher
 * trotzdem "✓ real ausgeführt". */
@Composable
private fun describeKioskDashboardDecision(outcome: SensitiveActionOutcome): String = when (outcome) {
    is SensitiveActionOutcome.Denied -> when (outcome.reason) {
        SensitiveActionDecisionResult.ExecutionBlocked -> stringResource(R.string.kiosk_outcome_execution_blocked)
        SensitiveActionDecisionResult.RateLimited -> stringResource(R.string.kiosk_outcome_rate_limited)
        SensitiveActionDecisionResult.WrongConfirmationText -> stringResource(R.string.kiosk_outcome_wrong_confirmation)
        SensitiveActionDecisionResult.PresenceNotProven -> stringResource(R.string.kiosk_outcome_presence_not_proven)
        SensitiveActionDecisionResult.Approved ->
            error("SensitiveActionOutcome.Denied wird nie mit reason=Approved erzeugt, s. dessen Klassendoc")
    }
    SensitiveActionOutcome.ExecutedSuccessfully -> stringResource(R.string.kiosk_outcome_executed_successfully)
    is SensitiveActionOutcome.ExecutedWithError -> stringResource(R.string.kiosk_outcome_executed_with_error, outcome.detail)
    SensitiveActionOutcome.ExecutedAsStub -> error("Kiosk-Dashboard-Knopf löst nie WIPE_DATA aus")
}

/** ChildVPN (2026-08-31) — kurze technische Fehlerursache für [R.string.network_child_vpn_parse_error]s
 * `%1$s`-Platzhalter. Bewusst NICHT über `stringResource`/strings.xml (wie [describeKioskDashboardDecision]
 * oben), weil dies aus einem `networkScope.launch`-Coroutine-Kontext aufgerufen wird, nicht aus
 * Composable-Scope — dieselbe Ausnahme wie bei den unkonvertierten domain-Enum-`.label`-Feldern
 * (s. CLAUDE.md "strings.xml-Extraktion"), nur hier durch den Aufrufkontext statt die Layer-Reinheits-
 * Regel begründet. Ein `IllegalArgumentException` aus [de.ble1st.warden.domain.netlock.ChildVpnConfig]s
 * eigener `init`-Validierung (z. B. falsche Schlüssellänge) ist kein [ChildVpnConfigParseException] —
 * dessen `e.message` ist bereits eine verständliche deutsche Meldung, kein zweites Mapping nötig. */
private fun describeChildVpnParseError(e: Throwable): String {
    val error = (e as? ChildVpnConfigParseException)?.error ?: return e.message ?: e.toString()
    return when (error) {
        ChildVpnConfigParseError.MissingInterfaceSection -> "kein [Interface]-Abschnitt gefunden"
        ChildVpnConfigParseError.MissingPeerSection -> "kein [Peer]-Abschnitt gefunden"
        ChildVpnConfigParseError.MissingPrivateKey -> "PrivateKey fehlt im [Interface]-Abschnitt"
        ChildVpnConfigParseError.MissingPeerPublicKey -> "PublicKey fehlt im [Peer]-Abschnitt"
        ChildVpnConfigParseError.MissingEndpoint -> "Endpoint fehlt im [Peer]-Abschnitt"
        ChildVpnConfigParseError.MissingAddress -> "Address fehlt im [Interface]-Abschnitt"
        is ChildVpnConfigParseError.MalformedAddress -> "ungültige Address-Angabe (IPv4 erwartet): ${error.raw}"
        is ChildVpnConfigParseError.MalformedDns -> "ungültige DNS-Angabe (IPv4 erwartet): ${error.raw}"
        is ChildVpnConfigParseError.MalformedEndpoint -> "ungültiges Endpoint-Format: ${error.raw}"
        is ChildVpnConfigParseError.InvalidBase64Key -> "${error.field} ist kein gültiger Base64-Schlüssel"
        is ChildVpnConfigParseError.InvalidValue -> "${error.field} hat einen ungültigen Wert: ${error.raw}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WardenStatusScreen(
    isDeviceOwner: Boolean,
    versionName: String,
    isDebuggableOs: Boolean,
    buildType: String,
    suspiciousFindingCount: Int,
    findingsLoadFailed: Boolean,
    /** Höchster Schweregrad unter den aktuellen Funden, `null` wenn keine Funde vorliegen oder
     * das Laden fehlschlug (Vorschlag V-2, 2026-08-29). */
    highestFindingSeverity: ThreatSeverity?,
    /** Zuletzt angewandtes Profil; `null` = noch nie eines angewandt (Vorschlag V-1). */
    activeProfile: WardenProfile?,
    onOpenFailsafe: () -> Unit,
    onOpenSensitiveAction: () -> Unit,
    onOpenPinManagement: () -> Unit,
    onOpenAppManagement: () -> Unit,
    onOpenSecurityScanner: () -> Unit,
    onOpenSafeguards: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
    onLockNow: () -> Unit,
    kioskQuickTriggerVisible: Boolean,
    kioskTriggerProfile: LockdownTriggerProfile,
    onKioskNow: () -> SensitiveActionOutcome,
    onOpenPermissionAudit: () -> Unit,
    onOpenPerformanceMonitor: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenSecurityScore: () -> Unit,
) {
    // Punkt 4 ("weitere App-UI-Verschönerungen", 2026-08-22) — haptisches Feedback für die einzige
    // sofort (ohne Bestätigungsschritt) ausgeführte Dashboard-Aktion, s. NumpadButton-Kommentar in
    // WardenPinActivity für dieselbe Begründung/Typwahl.
    val haptic = LocalHapticFeedback.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(imageVector = Icons.Filled.Settings, contentDescription = stringResource(R.string.content_description_settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            StatusCard(
                isDeviceOwner = isDeviceOwner,
                versionName = versionName,
                isDebuggableOs = isDebuggableOs,
                buildType = buildType,
                activeProfile = activeProfile,
            )

            SectionLabel(stringResource(R.string.section_device_protection))
            MenuRow(
                title = stringResource(R.string.menu_safeguards_title),
                subtitle = stringResource(R.string.menu_safeguards_subtitle),
                tag = "SG",
                onClick = onOpenSafeguards,
            )
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

            SectionLabel(stringResource(R.string.section_app_security))
            MenuRow(title = stringResource(R.string.menu_app_management_title), tag = "AV", onClick = onOpenAppManagement)
            MenuRow(
                title = stringResource(R.string.menu_security_scanner_title),
                tag = "SC",
                // Vorschlag V-2 (2026-08-29): Anzahl UND Schweregrad. Die Zahl allein beantwortet
                // die eigentliche Frage nicht — ein kritischer Fund (frisch aktivierter
                // Geräteadmin, Signaturwechsel) verlangt sofortiges Handeln, drei Infos
                // ("unbekannte Installationsquelle") auf einem Gerät, das selbst als Sideload-APK
                // läuft, gar keins.
                subtitle = when {
                    findingsLoadFailed -> null
                    highestFindingSeverity != null -> stringResource(
                        R.string.menu_security_scanner_highest_severity,
                        severityLabel(highestFindingSeverity),
                    )
                    else -> null
                },
                badge = when {
                    findingsLoadFailed -> "!"
                    suspiciousFindingCount > 0 -> suspiciousFindingCount.toString()
                    else -> null
                },
                // Rot nur bei kritischem Fund oder Lesefehler — sonst verliert die Farbe genau
                // die Dringlichkeit, die sie transportieren soll.
                badgeAlarming = findingsLoadFailed || highestFindingSeverity == ThreatSeverity.CRITICAL,
                onClick = onOpenSecurityScanner,
            )
            MenuRow(
                title = stringResource(R.string.menu_permission_audit_title),
                subtitle = stringResource(R.string.menu_permission_audit_subtitle),
                tag = "PA",
                onClick = onOpenPermissionAudit,
            )
            MenuRow(
                title = stringResource(R.string.menu_performance_monitor_title),
                subtitle = stringResource(R.string.menu_performance_monitor_subtitle),
                tag = "PM",
                onClick = onOpenPerformanceMonitor,
            )
            MenuRow(
                title = stringResource(R.string.menu_network_title),
                subtitle = stringResource(R.string.menu_network_subtitle),
                tag = "NW",
                onClick = onOpenNetwork,
            )
            MenuRow(
                title = stringResource(R.string.menu_security_score_title),
                subtitle = stringResource(R.string.menu_security_score_subtitle),
                tag = "SCR",
                onClick = onOpenSecurityScore,
            )
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

            SectionLabel(stringResource(R.string.section_access_confirmation))
            // Einziger Weg zu einer Ersteinrichtung/Änderung des Warden-PIN — startet
            // WardenPinActivity ohne EXTRA_PRESENCE_REQUEST (s. onOpenPinManagement-Doc oben).
            MenuRow(title = stringResource(R.string.menu_pin_management_title), tag = "PIN", onClick = onOpenPinManagement)
            // "LOCK_NOW als Device Command" (2026-08-22) — bewusst als eigener, sofort ausgeführter
            // Menüpunkt statt nur über "Sensible Aktion" erreichbar (dort weiterhin zusätzlich
            // vorhanden, presence-gated); s. ConcordBus.lockNow()/DeviceLockNowManager-Klassendoc,
            // warum kein Bestätigungsschritt nötig ist.
            MenuRow(
                title = stringResource(R.string.menu_lock_now_title),
                subtitle = stringResource(R.string.menu_lock_now_subtitle),
                tag = "LOCK",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLockNow()
                },
            )
            // "Lockdown-Auslöse-Profil" (2026-08-27) — nur sichtbar bei Profil ≠ STRICT +
            // installiertem Sentinel + bestätigtem Notruf-Drill (s. Sichtbarkeits-Berechnung in
            // WardenRoot). Läuft weiterhin vollständig durch DestructiveActionExecutor
            // (onKioskNow) — kein ungegateter Kurzschluss wie bei "Jetzt sperren" oben, da
            // LOCKDOWN_TASK_ENGAGE anders als LOCK_NOW schwer rückgängig zu machen ist.
            if (kioskQuickTriggerVisible) {
                var showKioskConfirm by remember { mutableStateOf(false) }
                // Vorschlag V-7 (2026-08-29): das Ergebnis statt nur seines Texts. Befund Q-5 hat
                // dafür gesorgt, dass ein fehlgeschlagenes Scharfschalten hier nicht mehr als
                // Erfolg *formuliert* wird — es sah aber weiterhin genauso aus, weil beide Fälle
                // in derselben gedämpften Farbe standen.
                var kioskOutcome by remember { mutableStateOf<SensitiveActionOutcome?>(null) }
                MenuRow(
                    title = stringResource(R.string.menu_kiosk_now_title),
                    subtitle = stringResource(R.string.menu_kiosk_now_subtitle),
                    tag = "KIOSK",
                    onClick = {
                        if (LockdownTriggerProfilePolicy.requiresConfirmationDialog(kioskTriggerProfile)) {
                            showKioskConfirm = true
                        } else {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            kioskOutcome = onKioskNow()
                        }
                    },
                )
                kioskOutcome?.let { outcome ->
                    val failed = outcome !is SensitiveActionOutcome.ExecutedSuccessfully
                    Text(
                        text = describeKioskDashboardDecision(outcome),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (showKioskConfirm) {
                    AlertDialog(
                        onDismissRequest = { showKioskConfirm = false },
                        title = { Text(stringResource(R.string.kiosk_confirm_title)) },
                        text = { Text(stringResource(R.string.kiosk_confirm_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showKioskConfirm = false
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                kioskOutcome = onKioskNow()
                            }) { Text(stringResource(R.string.action_yes)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showKioskConfirm = false }) { Text(stringResource(R.string.action_no)) }
                        },
                    )
                }
            }
            MenuRow(title = stringResource(R.string.menu_sensitive_action_title), tag = "SA", onClick = onOpenSensitiveAction)
            MenuRow(title = stringResource(R.string.menu_log_viewer_title), tag = "LOG", onClick = onOpenLog)
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

            SectionLabel(stringResource(R.string.section_recovery))
            MenuRow(title = stringResource(R.string.menu_offline_failsafe_title), tag = "FS", onClick = onOpenFailsafe)
        }
    }
}

@Composable
private fun StatusCard(
    isDeviceOwner: Boolean,
    versionName: String,
    isDebuggableOs: Boolean,
    buildType: String,
    activeProfile: WardenProfile?,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(if (isDeviceOwner) R.string.status_do_active else R.string.status_do_inactive),
                style = MaterialTheme.typography.titleLarge,
                // Fail-Safe-Grundsatz (Invariante 6) gilt auch für die Anzeige selbst: ein
                // fehlender/negativer Zustand wird auffällig dargestellt, nie beschönigt.
                color = if (isDeviceOwner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.status_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Vorschlag V-1 (2026-08-29): das aktive Profil gehört auf den ersten Bildschirm.
            // AutoProfileController schaltet es auch ohne Zutun um (Nachtfenster, Eskalation bei
            // kritischem Fund) — ohne diese Zeile war eine automatische Umschaltung nur im
            // Audit-Log zu sehen. "keins" statt eines geratenen Vorgabewerts, s. ProfilePicker.
            Text(
                text = stringResource(
                    R.string.status_profile,
                    activeProfile?.label ?: stringResource(R.string.status_profile_none),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isDebuggableOs) {
                Text(
                    text = stringResource(R.string.status_debuggable_os_warning, buildType),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
