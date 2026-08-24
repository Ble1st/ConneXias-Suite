package de.ble1st.warden.ui

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
import androidx.compose.ui.unit.dp
import de.ble1st.warden.WardenApplication
import de.ble1st.warden.admin.DeviceOwnerStatusReader
import de.ble1st.warden.appmanagement.AppManagementInfo
import de.ble1st.warden.appmanagement.SuspiciousAppFindingInfo
import de.ble1st.warden.autoreboot.AutoRebootStorage
import de.ble1st.warden.bus.ConcordBus
import de.ble1st.warden.domain.profile.WardenProfile
import de.ble1st.warden.domain.frp.FactoryResetProtectionAccounts
import de.ble1st.warden.domain.frp.FactoryResetProtectionDecision
import de.ble1st.warden.registry.FactoryResetProtectionSafeguard
import de.ble1st.warden.failsafe.FailsafeActivity
import de.ble1st.warden.integrity.DebuggableOsStatusReader
import de.ble1st.warden.integrity.DeviceIntegrityStatus
import de.ble1st.warden.pin.WardenLockScreenTextStorage
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
import de.ble1st.warden.registry.UsbDataSignalingSafeguard
import de.ble1st.warden.registry.UserRestrictionSafeguard
import de.ble1st.warden.registry.WardenFactoryResetProtectionStorage
import de.ble1st.warden.registry.WardenOrganizationNameStorage
import de.ble1st.warden.registry.WardenSupportMessageStorage
import de.ble1st.warden.ui.theme.WardenAccent
import de.ble1st.warden.ui.theme.WardenTheme
import de.ble1st.warden.ui.theme.WardenThemePrefs
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

    private val lockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        lockRequestInFlight = false
        if (result.resultCode == RESULT_OK) {
            authenticated.value = true
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
            val isAuthenticated by authenticated
            WardenTheme(accent = accent) {
                if (!isAuthenticated) {
                    // WardenLockActivity läuft parallel (onResume() oben) — hier nur ein
                    // Platzhalter, kein Dashboard-Inhalt darf davor sichtbar sein/gerendert werden.
                    LoadingScreen(title = "Warden", onBack = { finish() })
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
            return
        }
        authenticated.value = false
        if (!lockRequestInFlight) {
            lockRequestInFlight = true
            lockLauncher.launch(Intent(this, WardenLockActivity::class.java))
        }
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
            WardenStatusScreen(
                isDeviceOwner = isDeviceOwner,
                versionName = versionName,
                isDebuggableOs = isDebuggableOs,
                buildType = buildType,
                suspiciousFindingCount = findingsResult?.size ?: 0,
                findingsLoadFailed = findingsResult == null,
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
                LoadingScreen(title = "App-Verwaltung", onBack = { screen = WardenScreen.Status })
            } else {
                AppManagementScreen(
                    apps = appsResult.orEmpty(),
                    loadFailed = appsResult == null,
                    onBack = { screen = WardenScreen.Status },
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
                LoadingScreen(title = "Sicherheits-Scanner", onBack = { screen = WardenScreen.Status })
            } else {
                SecurityScannerScreen(
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
            var catalogGeneration by remember { mutableIntStateOf(0) }
            var lockdownModeActive by remember { mutableStateOf(loadLockdownModeActiveSafely(concordBus)) }
            var frpAccounts by remember {
                mutableStateOf(
                    WardenFactoryResetProtectionStorage.load(appContext).joinToString("\n"),
                )
            }
            var frpAgentAvailable by remember {
                mutableStateOf(FactoryResetProtectionSafeguard(appContext).isFrpAgentAvailable())
            }
            var profileApplyWarning by remember { mutableStateOf<String?>(null) }
            key(catalogGeneration) {
                SafeguardsScreen(
                    cameraLocked = rememberSafeguardToggle(concordBus, CameraSafeguard.ID),
                    screenCaptureLocked = rememberSafeguardToggle(concordBus, ScreenCaptureSafeguard.ID),
                    microphoneMuted = rememberSafeguardToggle(concordBus, UserRestrictionSafeguard.MICROPHONE_MUTED_ID),
                    clockIntegrity = rememberSafeguardToggle(concordBus, UserRestrictionSafeguard.CONFIG_DATE_TIME_DISABLED_ID),
                    selfUninstallProtection = rememberSafeguardToggle(concordBus, SelfUninstallProtectionSafeguard.ID),
                    forceStopProtection = rememberSafeguardToggle(concordBus, ForceStopProtectionSafeguard.ID),
                    credentialConfigLockdown = rememberSafeguardToggle(concordBus, UserRestrictionSafeguard.CREDENTIAL_CONFIG_DISABLED_ID),
                    physicalMediaMountLockdown = rememberSafeguardToggle(concordBus, UserRestrictionSafeguard.PHYSICAL_MEDIA_MOUNT_DISABLED_ID),
                    keyguardHardening = rememberSafeguardToggle(concordBus, KeyguardHardeningSafeguard.ID),
                    accessibilityLockdown = rememberSafeguardToggle(concordBus, AccessibilityLockdownSafeguard.ID),
                    inputMethodLockdown = rememberSafeguardToggle(concordBus, InputMethodLockdownSafeguard.ID),
                    securityLogging = rememberSafeguardToggle(concordBus, SecurityLoggingSafeguard.ID),
                    networkLogging = rememberSafeguardToggle(concordBus, NetworkLoggingSafeguard.ID),
                    passwordComplexity = rememberSafeguardToggle(concordBus, PasswordComplexitySafeguard.ID),
                    autoLockTimeout = rememberSafeguardToggle(concordBus, AutoLockTimeoutSafeguard.ID),
                    backupServiceLockdown = rememberSafeguardToggle(concordBus, BackupServiceLockdownSafeguard.ID),
                    systemUpdatePolicy = rememberSafeguardToggle(concordBus, SystemUpdatePolicySafeguard.ID),
                    lockScreenPrivacy = rememberSafeguardToggle(concordBus, LockScreenPrivacySafeguard.ID),
                    usbAutoLock = rememberUsbAutoLockToggle(concordBus),
                    usbPermanentlyDisabled = rememberSafeguardToggle(concordBus, UsbDataSignalingSafeguard.ID),
                    installUnknownSourcesDisabled = rememberSafeguardToggle(
                        concordBus,
                        UserRestrictionSafeguard.INSTALL_UNKNOWN_SOURCES_DISABLED_ID,
                    ),
                    factoryResetDisabled = rememberSafeguardToggle(
                        concordBus,
                        UserRestrictionSafeguard.FACTORY_RESET_DISABLED_ID,
                    ),
                    safeBootDisabled = rememberSafeguardToggle(
                        concordBus,
                        UserRestrictionSafeguard.SAFE_BOOT_DISABLED_ID,
                    ),
                    factoryResetProtection = rememberSafeguardToggle(
                        concordBus,
                        FactoryResetProtectionSafeguard.ID,
                    ),
                    modifyAccountsDisabled = rememberSafeguardToggle(
                        concordBus,
                        UserRestrictionSafeguard.MODIFY_ACCOUNTS_DISABLED_ID,
                    ),
                    factoryResetProtectionAccounts = frpAccounts,
                    factoryResetProtectionAgentAvailable = frpAgentAvailable,
                    onSaveFactoryResetProtectionAccounts = { raw ->
                        when (val decision = FactoryResetProtectionAccounts.evaluateRaw(raw)) {
                            is FactoryResetProtectionDecision.Valid -> {
                                WardenFactoryResetProtectionStorage.save(appContext, decision.accounts)
                                frpAccounts = decision.accounts.joinToString("\n")
                                runCatching {
                                    concordBus.applySafeguard(FactoryResetProtectionSafeguard.ID)
                                    concordBus.applySafeguard(UserRestrictionSafeguard.MODIFY_ACCOUNTS_DISABLED_ID)
                                }.onFailure { Log.e("WardenStatus", "FRP-Konten-Abgleich fehlgeschlagen", it) }
                                frpAgentAvailable = FactoryResetProtectionSafeguard(appContext).isFrpAgentAvailable()
                                if (!frpAgentAvailable) {
                                    Log.w("WardenStatus", "FRP aktiviert, aber Google-Play-Dienste nicht gefunden — Policy wird vermutlich nicht durchgesetzt")
                                }
                            }
                            FactoryResetProtectionDecision.Empty -> {
                                WardenFactoryResetProtectionStorage.save(appContext, emptyList())
                                frpAccounts = ""
                                runCatching {
                                    concordBus.revertSafeguard(FactoryResetProtectionSafeguard.ID)
                                }.onFailure { Log.e("WardenStatus", "FRP-Konten-Abgleich fehlgeschlagen", it) }
                            }
                            FactoryResetProtectionDecision.TooMany ->
                                Log.w("WardenStatus", "FRP: höchstens ${FactoryResetProtectionAccounts.MAX_ACCOUNTS} Konten")
                            FactoryResetProtectionDecision.TooLong ->
                                Log.w("WardenStatus", "FRP: Konto zu lang (max ${FactoryResetProtectionAccounts.MAX_ACCOUNT_LENGTH})")
                        }
                        catalogGeneration++
                    },
                    lockdownModeActive = lockdownModeActive,
                    profileApplyWarning = profileApplyWarning,
                    onApplyProfile = { profile: WardenProfile ->
                        runCatching { concordBus.applyProfile(profile) }
                            .onSuccess { result ->
                                profileApplyWarning = when {
                                    result.failed.isNotEmpty() ->
                                        "Profil ${profile.label}: fehlgeschlagen für ${result.failed.joinToString()}"
                                    result.skipped.isNotEmpty() ->
                                        "Profil ${profile.label}: ohne FRP-Konten angewendet — " +
                                            "Kontosperre nach Reset bleibt aus, bis Konten hinterlegt sind."
                                    else -> null
                                }
                            }
                            .onFailure {
                                Log.e("WardenStatus", "Profil-Anwendung fehlgeschlagen", it)
                                profileApplyWarning = "Profil ${profile.label}: Anwendung fehlgeschlagen."
                            }
                        catalogGeneration++
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
                onBack = { screen = WardenScreen.Status },
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
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
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

/** Tier 1/2/3/5 (2026-08-22) — generisches Gegenstück zum `screenCaptureResult`-Muster oben, für
 * acht weitere, gleich verkabelte [SafeguardToggleState]-Schalter (s. dortiges Klassendoc). */
@Composable
private fun rememberSafeguardToggle(concordBus: ConcordBus, safeguardId: String): SafeguardToggleState {
    var result by remember { mutableStateOf(loadSafeguardActiveSafely(concordBus, safeguardId)) }
    return SafeguardToggleState(
        locked = result,
        onToggle = { requested ->
            runCatching {
                if (requested) concordBus.applySafeguard(safeguardId) else concordBus.revertSafeguard(safeguardId)
            }.onFailure { Log.e("WardenStatus", "Safeguard-Schalter ($safeguardId) fehlgeschlagen", it) }
            result = loadSafeguardActiveSafely(concordBus, safeguardId)
        },
    )
}

/** "USB automatisch sperren bei Bildschirmsperre" (2026-08-22) — dasselbe generische
 * [SafeguardToggleState]-Muster wie [rememberSafeguardToggle], aber gegen
 * [ConcordBus.isUsbAutoLockEnabled]/[ConcordBus.setUsbAutoLockEnabled] statt gegen die
 * `Safeguard`-Registry: diese Funktion schaltet keine Safeguard-`apply()`/`revert()`, sondern nur
 * eine lokale Präferenz, die [de.ble1st.warden.usb.UsbAutoLockController] periodisch ausliest. */
@Composable
private fun rememberUsbAutoLockToggle(concordBus: ConcordBus): SafeguardToggleState {
    var result by remember { mutableStateOf(loadUsbAutoLockEnabledSafely(concordBus)) }
    return SafeguardToggleState(
        locked = result,
        onToggle = { requested ->
            runCatching { concordBus.setUsbAutoLockEnabled(requested) }
                .onFailure { Log.e("WardenStatus", "USB-Auto-Lock-Schalter fehlgeschlagen", it) }
            result = loadUsbAutoLockEnabledSafely(concordBus)
        },
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
 * [loadManagedAppsSafely]/[loadFindingsSafely]/[loadSafeguardActiveSafely]/[loadScannerEnabledSafely]
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

private fun loadSafeguardActiveSafely(bus: ConcordBus, safeguardId: String): Boolean? =
    runCatching { bus.isSafeguardActive(safeguardId) }
        .onFailure { Log.e("WardenStatus", "Safeguard-Status ($safeguardId) nicht ladbar", it) }
        .getOrNull()

private fun loadDeviceIntegrityStatusSafely(bus: ConcordBus): DeviceIntegrityStatus? =
    runCatching { bus.deviceIntegrityStatus() }
        .onFailure { Log.e("WardenStatus", "Geräte-Integritätsstatus nicht ladbar", it) }
        .getOrNull()

/** "Arbeite langsam am Lockdownmodus" (2026-08-22), dritter Schritt — reiner Lesepfad, s.
 * [ConcordBus.isLockdownModeActive]-Doc: kein Schalter, nur Statusanzeige. */
private fun loadLockdownModeActiveSafely(bus: ConcordBus): Boolean? =
    runCatching { bus.isLockdownModeActive() }
        .onFailure { Log.e("WardenStatus", "Lockdown-Modus-Status nicht ladbar", it) }
        .getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WardenStatusScreen(
    isDeviceOwner: Boolean,
    versionName: String,
    isDebuggableOs: Boolean,
    buildType: String,
    suspiciousFindingCount: Int,
    findingsLoadFailed: Boolean,
    onOpenFailsafe: () -> Unit,
    onOpenSensitiveAction: () -> Unit,
    onOpenPinManagement: () -> Unit,
    onOpenAppManagement: () -> Unit,
    onOpenSecurityScanner: () -> Unit,
    onOpenSafeguards: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
    onLockNow: () -> Unit,
) {
    // Punkt 4 ("weitere App-UI-Verschönerungen", 2026-08-22) — haptisches Feedback für die einzige
    // sofort (ohne Bestätigungsschritt) ausgeführte Dashboard-Aktion, s. NumpadButton-Kommentar in
    // WardenPinActivity für dieselbe Begründung/Typwahl.
    val haptic = LocalHapticFeedback.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Warden") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(imageVector = Icons.Filled.Settings, contentDescription = "Einstellungen")
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
            )

            SectionLabel("Geräteschutz")
            MenuRow(title = "Safeguards", subtitle = "Profile, Reset-Schutz, Sperrbildschirm", tag = "SG", onClick = onOpenSafeguards)
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

            SectionLabel("App-Sicherheit")
            MenuRow(title = "App-Verwaltung", tag = "AV", onClick = onOpenAppManagement)
            MenuRow(
                title = "Sicherheits-Scanner",
                tag = "SC",
                badge = when {
                    findingsLoadFailed -> "!"
                    suspiciousFindingCount > 0 -> suspiciousFindingCount.toString()
                    else -> null
                },
                onClick = onOpenSecurityScanner,
            )
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

            SectionLabel("Zugriff & Bestätigung")
            // Einziger Weg zu einer Ersteinrichtung/Änderung des Warden-PIN — startet
            // WardenPinActivity ohne EXTRA_PRESENCE_REQUEST (s. onOpenPinManagement-Doc oben).
            MenuRow(title = "Warden-PIN verwalten", tag = "PIN", onClick = onOpenPinManagement)
            // "LOCK_NOW als Device Command" (2026-08-22) — bewusst als eigener, sofort ausgeführter
            // Menüpunkt statt nur über "Sensible Aktion" erreichbar (dort weiterhin zusätzlich
            // vorhanden, presence-gated); s. ConcordBus.lockNow()/DeviceLockNowManager-Klassendoc,
            // warum kein Bestätigungsschritt nötig ist.
            MenuRow(
                title = "Jetzt sperren",
                subtitle = "Sofort, ohne Bestätigung",
                tag = "LOCK",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLockNow()
                },
            )
            MenuRow(title = "Sensible Aktion", tag = "SA", onClick = onOpenSensitiveAction)
            MenuRow(title = "Log-Einsicht", tag = "LOG", onClick = onOpenLog)
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

            SectionLabel("Wiederherstellung")
            MenuRow(title = "Offline-Failsafe", tag = "FS", onClick = onOpenFailsafe)
        }
    }
}

@Composable
private fun StatusCard(isDeviceOwner: Boolean, versionName: String, isDebuggableOs: Boolean, buildType: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (isDeviceOwner) "DO aktiv" else "DO NICHT aktiv",
                style = MaterialTheme.typography.titleLarge,
                // Fail-Safe-Grundsatz (Invariante 6) gilt auch für die Anzeige selbst: ein
                // fehlender/negativer Zustand wird auffällig dargestellt, nie beschönigt.
                color = if (isDeviceOwner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(
                text = "Version $versionName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isDebuggableOs) {
                Text(
                    text = "⚠ Debuggable OS (Build.TYPE=$buildType) — Vertrauensmodell setzt user-Build voraus",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
