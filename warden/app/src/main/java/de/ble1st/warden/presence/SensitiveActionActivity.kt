package de.ble1st.warden.presence

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import de.ble1st.warden.BuildConfig
import de.ble1st.warden.R
import de.ble1st.warden.WardenApplication
import de.ble1st.warden.admin.OwnershipTransferTargetReader
import de.ble1st.warden.admin.WardenDeviceAdminReceiver
import de.ble1st.warden.domain.presence.DestructiveCommandGuard
import de.ble1st.warden.domain.presence.SensitiveAction
import de.ble1st.warden.domain.presence.SensitiveActionDecisionResult
import de.ble1st.warden.domain.presence.SensitiveActionOutcome
import de.ble1st.warden.domain.pin.LockdownTriggerProfile
import de.ble1st.warden.domain.pin.LockdownTriggerProfilePolicy
import de.ble1st.warden.domain.registry.SafeguardRegistry
import de.ble1st.warden.pin.LockdownTriggerProfileStore
import de.ble1st.warden.pin.WardenLockTaskDrillFreshnessGate
import de.ble1st.warden.sentinelbridge.SentinelLockdownEngager
import de.ble1st.warden.registry.DeviceLockNowManager
import de.ble1st.warden.registry.DeviceLockdownBundle
import de.ble1st.warden.registry.MasterSwitch
import de.ble1st.warden.registry.PersistentSafeguardRegistry
import de.ble1st.warden.registry.RegistryStorage
import de.ble1st.warden.registry.SafeguardCatalog
import de.ble1st.warden.registry.SafeguardRegistryStore
import de.ble1st.warden.wardenAuditLog
import de.ble1st.warden.ui.theme.WardenTheme
import de.ble1st.warden.ui.theme.WardenThemePrefs
import kotlinx.coroutines.delay

/**
 * Meilenstein F.2 (Konzept Abschnitt 2b/(7) Punkt 3): "Bestätigung out-of-band anzeigen ...
 * Warden selbst ... der Nutzer sieht, *was* bestätigt wird." Zwei-Schritt-Bestätigung (Konzept
 * Abschnitt 8, "Dialog + Presence + Bestätigungstext"): erst Text exakt eintippen, dann
 * Presence — die beiden Presence-Buttons bleiben deaktiviert, bis der Text passt, damit kein
 * unnötiger Prompt/PIN-Screen-Start ausgelöst wird, wenn das ohnehin scheitern würde.
 *
 * `FragmentActivity` statt `ComponentActivity` — Plattform-Anforderung von
 * `androidx.biometric.BiometricPrompt` ([PresenceManager]-Klassendoc).
 *
 * **`REBOOT`/`MASTER_SWITCH_REVERT`/`LOCK_NOW`/`LOCKDOWN_MODE_ARM`/`LOCKDOWN_TASK_ENGAGE`
 * (seit 2026-08-25) real verkabelt, `WIPE_DATA` bewusst weiterhin Stub**
 * ([DestructiveActionExecutor]-Klassendoc für die Begründung).
 *
 * **`LOCKDOWN_MODE_ARM` (2026-08-22, "arbeite langsam am Lockdownmodus" — erster, bewusst
 * kleiner Schritt, presence-gated auf ausdrücklichen Nutzerwunsch statt als einfacher
 * Safeguard-Schalter):** anders als bisher dokumentiert nimmt die lokale Registry hier jetzt
 * auch [DeviceLockdownBundle] auf (C.5 — USB-Signaling aus, `DISALLOW_SAFE_BOOT`/
 * `DISALLOW_FACTORY_RESET`/`DISALLOW_DEBUGGING_FEATURES`, Lock-Task-Autorisierung), scharf
 * geschaltet über `registry.apply(DeviceLockdownBundle.ID)` statt einer wegwerfbaren
 * Bündel-Instanz — genau wie jeder andere Registry-Eintrag, damit der gewünschte Zustand
 * persistiert (`PersistentSafeguardRegistry`-Klassendoc) und nach einem Neustart erhalten
 * bleibt. Bewusst **kein** eigenes `LOCKDOWN_MODE_REVERT`: weil das Bündel jetzt in derselben
 * Registry steckt wie `CameraSafeguard`/`ScreenCaptureSafeguard`/`installUnknownSourcesDisabled`,
 * wird es vom bereits bestehenden Masterschalter automatisch mit zurückgesetzt —
 * `MASTER_SWITCH_REVERT` ist also ab sofort auch der Rückweg für den Lockdown-Modus, ein
 * zweiter, paralleler presence-gated Revert-Pfad wäre nur unnötig redundant. Real ausgeführt
 * wird das trotzdem nirgends auf dem aktuellen Testgerät: `DestructiveCommandGuard` (F.4)
 * blockiert jede `SensitiveAction` hart, solange es sich um einen Debug-Build handelt — und das
 * tut das angeschlossene Testgerät ausnahmslos. Die vier ursprünglichen C.5-Risiken
 * (Werksreset-Blockade, gekappte `adb`-Verbindung, s. [DeviceLockdownBundle]-Klassendoc) bleiben
 * deshalb unverändert bestehen, sobald dieser Guard einmal nicht mehr greift (Non-Debug-Build) —
 * das bleibt eine bewusste, separate künftige Entscheidung, kein Nebeneffekt dieser Verkabelung.
 *
 * **Presence-Reaktivierung über Wardens lokalen PIN (Threat Model T4: "Owner presence
 * (biometric / local PIN path)"):** Geräte ohne Class-3-Sensor haben über den Biometrie-Pfad
 * (F.1) sonst keinen Presence-Weg — "Mit Warden-PIN bestätigen" startet [WardenPinActivity]
 * per `startActivityForResult` mit [WardenPinActivity.EXTRA_PRESENCE_REQUEST]; `RESULT_OK`
 * bedeutet eine **soeben real verifizierte** PIN.
 *
 * Anders als im ConneXias-Framework-Quellprojekt (dort: `SentinelActivity` in einer fremden
 * APK, deshalb per explizitem Cross-APK-`ComponentName` samt vorab geprüfter
 * Signatur-Lineage/Zertifikats-Pinning angesprochen) ist [WardenPinActivity] eine Activity
 * dieser eigenen APK — ein einfacher `Intent(this, WardenPinActivity::class.java)` genügt, kein
 * `SignatureLineageVerifier`/Zertifikats-Pinning mehr nötig (kein fremdes Paket mehr, das
 * ersetzt/neu signiert worden sein könnte).
 */
class SensitiveActionActivity : FragmentActivity() {

    private var pendingPinPresenceResult: ((granted: Boolean) -> Unit)? = null

    private val wardenLockSession by lazy { (application as WardenApplication).wardenLockSession }

    /** WardenLock (Finalisierungsphase 2026-08-24): Backgrounding **während** diese Activity
     * offen ist (z. B. Home-Taste mitten in einem Reboot-Bestätigungsdialog) invalidiert die
     * Sitzung genau wie überall sonst — s. [finishIfWardenLockSessionMissing]-Klassendoc. Ohne
     * diesen Check hier würde `WardenStatusActivity.onResume()` gar nicht erst laufen (sie ist
     * unterhalb dieser Activity im Task-Stack pausiert), die Invalidierung also wirkungslos
     * bleiben, solange der Nutzer diese Activity nicht selbst verlässt. */
    override fun onResume() {
        super.onResume()
        finishIfWardenLockSessionMissing(wardenLockSession)
    }

    /** Muss vor `STARTED` registriert werden — als Property-Initializer läuft das während der
     * Konstruktion, lange vor `onCreate()`, s. androidx-Dokumentation zu
     * `registerForActivityResult`. */
    private val pinPresenceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        val callback = pendingPinPresenceResult
        pendingPinPresenceResult = null
        callback?.invoke(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        // Tier 3 (2026-09-05): nur gesetzt, wenn dieser Bildschirm aus den erweiterten
        // Einstellungen heraus mit einem konkreten Übertragungsziel geöffnet wurde. Ohne Ziel
        // bleibt TRANSFER_OWNERSHIP unten aus der Auswahlliste heraus (s. `selectableActions`)
        // *und* das ausführende Lambda meldet `false` — zwei unabhängige Sperren für denselben
        // Fehlerfall, dieselbe Defense-in-Depth-Haltung wie beim Presence-Check selbst.
        val transferTarget = intent.getStringExtra(EXTRA_TRANSFER_TARGET)
            ?.let { ComponentName.unflattenFromString(it) }
            // Defense-in-Depth: das Ziel aus dem Intent gegen die Liste der verfügbaren Ziele
            // validieren — `OwnershipTransferTargetReader.availableTargets()` listet nur Apps
            // mit deklariertem DeviceAdminReceiver, die die Rolle tatsächlich übernehmen können.
            // Ohne diesen Check könnte ein präparierter Intent ein beliebiges ComponentName
            // übergeben, das zwar einen DeviceAdminReceiver deklariert, aber von Warden nicht
            // als vertrauenswürdig eingestuft wurde.
            ?.takeIf { target ->
                OwnershipTransferTargetReader(this).availableTargets().any { it.receiver == target }
            }
        val executor = buildExecutor(this, transferTarget)
        val presenceManager = PresenceManager(this)
        val executionAllowed = DestructiveCommandGuard.isExecutionAllowed(BuildConfig.DEBUG)
        // Nur gelesen, nicht hier umschaltbar — s. FailsafeActivity-Kommentar.
        val accent = WardenThemePrefs.load(applicationContext)
        // "Arbeite langsam am Lockdownmodus" (2026-08-22), zweiter Schritt: reiner
        // Lese-Status, keine eigene Instanz mit Zustand — jeder Aufruf baut ein frisches
        // DeviceLockdownBundle und fragt isActive() live ab (Safeguard-Konvention: isActive()
        // fragt immer den echten DPM-Zustand ab, nie gecacht). Bewusst hier im selben Bildschirm
        // statt als Cross-Activity-Refresh in WardenStatusActivity — dort gibt es bislang keinen
        // Lifecycle-Refresh-Mechanismus, den für eine einzelne Statuszeile neu einzuführen wäre
        // ein größerer Schritt, als "langsam" hier gerechtfertigt ist.
        val checkLockdownActive = { isLockdownModeActive(applicationContext) }
        // "Lockdown-Auslöse-Profil" (2026-08-27): erlaubt es einem externen Einstiegspunkt
        // (SentinelQuickTile unter LockdownTriggerProfile.STRICT), diese Activity mit einer
        // bereits vorausgewählten Aktion zu öffnen, statt den Nutzer erneut aus allen sechs
        // Aktionen wählen zu lassen. Ein unbekannter/fehlender Wert fällt auf den bisherigen
        // Default zurück, kein Crash.
        val preselectedAction = intent.getStringExtra(EXTRA_PRESELECTED_ACTION)
            ?.let { runCatching { SensitiveAction.valueOf(it) }.getOrNull() }
            ?: SensitiveAction.MASTER_SWITCH_REVERT
        val lockdownTriggerProfile = LockdownTriggerProfileStore.load(applicationContext)

        setContent {
            WardenTheme(accent = accent) {
                SensitiveActionScreen(
                    executionAllowed = executionAllowed,
                    checkLockdownActive = checkLockdownActive,
                    sessionAuthenticated = wardenLockSession.isAuthenticated(),
                    initialAction = preselectedAction,
                    lockdownTriggerProfile = lockdownTriggerProfile,
                    transferTarget = transferTarget,
                    onConfirmSession = { action, confirmationText, onResult ->
                        val outcome = executor.executeWithSessionPresence(
                            action,
                            confirmationText,
                            sessionAuthenticated = wardenLockSession.isAuthenticated(),
                        )
                        onResult(describeOutcome(this, action, outcome))
                    },
                    onConfirmBiometric = { action, confirmationText, onResult ->
                        presenceManager.request(
                            title = getString(R.string.sensitive_action_biometric_prompt_title),
                            subtitle = describeAction(this, action),
                        ) { result ->
                            when (result) {
                                is PresenceManager.Result.Success -> {
                                    val outcome = executor.execute(action, confirmationText, result.proof)
                                    onResult(describeOutcome(this, action, outcome))
                                }
                                PresenceManager.Result.Unavailable ->
                                    onResult(getString(R.string.sensitive_action_biometric_unavailable))
                                PresenceManager.Result.Cancelled ->
                                    onResult(getString(R.string.sensitive_action_biometric_cancelled))
                            }
                        }
                    },
                    onConfirmPin = { action, confirmationText, onResult ->
                        pendingPinPresenceResult = { granted ->
                            val outcome = executor.executeWithPinPresence(action, confirmationText, granted)
                            onResult(describeOutcome(this, action, outcome))
                        }
                        pinPresenceLauncher.launch(
                            Intent(this, WardenPinActivity::class.java).apply {
                                putExtra(WardenPinActivity.EXTRA_PRESENCE_REQUEST, true)
                            },
                        )
                    },
                )
            }
        }
    }

    companion object {
        /** "Lockdown-Auslöse-Profil" (2026-08-27) — erlaubt [de.ble1st.warden.sentinelbridge
         * .SentinelQuickTile] (unter [de.ble1st.warden.domain.pin.LockdownTriggerProfile.STRICT])
         * diese Activity mit vorausgewähltem [SensitiveAction] zu öffnen, statt den Nutzer erneut
         * aus allen sechs Aktionen wählen zu lassen — dieselbe Idee wie
         * [WardenPinActivity.EXTRA_PRESENCE_REQUEST]. */
        const val EXTRA_PRESELECTED_ACTION = "de.ble1st.warden.presence.SensitiveActionActivity.EXTRA_PRESELECTED_ACTION"

        /** Tier 3 (2026-09-05): der `ComponentName` (flach serialisiert) des Admin-Empfängers, an
         * den [SensitiveAction.TRANSFER_OWNERSHIP] die Device-Owner-Rolle übergeben soll. Fehlt
         * dieses Extra, ist die Aktion in diesem Bildschirm gar nicht erst wählbar — ein
         * Übertragungsziel gehört zur Aktion, nicht in das parameterlose Enum. */
        const val EXTRA_TRANSFER_TARGET = "de.ble1st.warden.presence.SensitiveActionActivity.EXTRA_TRANSFER_TARGET"

        /** Verkabelt Presence-/Rate-Limit-/Bestätigungs-Kette mit den echten
         * `DevicePolicyManager`-Aufrufen für `REBOOT` und `MasterSwitch.disarm()` für
         * `MASTER_SWITCH_REVERT` — dieselben drei bekannten C.2-Schalter wie `FailsafeActivity`
         * und `RegistryReconciliationReceiver`. `WIPE_DATA` bleibt Stub (s. Klassendoc/
         * [DestructiveActionExecutor]).
         *
         * **`activity: Activity` statt bloß `Context`** (seit `LOCKDOWN_TASK_ENGAGE`,
         * 2026-08-25) — nicht mehr technisch zwingend seit "Sentinel: eigenständige Kiosk-PIN-
         * App" (`startLockTask()` läuft jetzt in Sentinels eigenem Prozess, nicht mehr hier), aber
         * beibehalten: kein Grund, die Signatur zu verschmälern, nur weil der konkrete Aufruf
         * jetzt ein reiner `Context`-Verbraucher ist. */
        private fun buildExecutor(activity: Activity, transferTarget: ComponentName?): DestructiveActionExecutor {
            val context = activity.applicationContext
            val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)
            val devicePolicyManager = checkNotNull(context.getSystemService(DevicePolicyManager::class.java)) {
                "DevicePolicyManager nicht verfügbar"
            }

            val registry = PersistentSafeguardRegistry(
                SafeguardRegistry(),
                SafeguardRegistryStore(RegistryStorage.buildEnvelopeFile(context)),
            )
            SafeguardCatalog.registerAll(registry, context)
            registry.load()
            val masterSwitch = MasterSwitch(registry)

            return DestructiveActionExecutor(
                isDebugBuild = BuildConfig.DEBUG,
                logStore = wardenAuditLog(context),
                performReboot = { devicePolicyManager.reboot(admin) },
                performMasterSwitchRevert = { masterSwitch.disarm() },
                // Eigene Idee (2026-08-22), dritte Ergänzungsrunde: Eviction-Flag-Logik jetzt in
                // DeviceLockNowManager gebündelt, s. dortiges Klassendoc — von hier UND vom neuen
                // "Jetzt sperren"-Dashboard-Befehl (ConcordBus.lockNow) genutzt.
                performLockNow = { DeviceLockNowManager(context).lockNow() },
                // registry.apply(id) statt einer wegwerfbaren build()-Instanz direkt zu benutzen —
                // damit der gewünschte Zustand über PersistentSafeguardRegistry persistiert wird,
                // genau wie bei jedem anderen Registry-Eintrag (Klassendoc oben).
                performLockdownArm = { registry.apply(DeviceLockdownBundle.ID) },
                // s. Klassendoc oben — WardenLockTaskDrillStorage wird hier, unmittelbar vor dem
                // tatsächlichen Aufruf, gelesen, nicht früher zwischengespeichert (derselbe
                // "nie ein potenziell veraltetes Bit cachen"-Vorbehalt wie bei jedem anderen
                // isActive()-Lesezugriff in diesem Projekt).
                performLockTaskEngage = {
                    SentinelLockdownEngager.engage(
                        context = context,
                        emergencyCallDrillPassed = WardenLockTaskDrillFreshnessGate.effectiveEmergencyCallDrillPassed(context),
                    )
                },
                // Tier 3 (2026-09-05): das Ziel ist hier eingeschlossen, statt es durch
                // `SensitiveAction` zu reichen — s. dessen Klassendoc. Ohne Ziel meldet das Lambda
                // `false`, und `DestructiveActionExecutor` macht daraus einen sichtbaren
                // Fehlschlag statt einer stillen Erfolgsmeldung.
                performTransferOwnership = {
                    if (transferTarget == null) {
                        false
                    } else {
                        // Das leere PersistableBundle ist Androids vorgesehener Weg, *keine* Zusatzdaten an
                        // den neuen Owner zu übergeben. Warden hat nichts zu übergeben: alles, was
                        // es hält (PIN-Blob, Schlüssel, Audit-Log), ist bewusst gerätelokal und an
                        // Wardens eigene UID gebunden — dieselbe Grenze, die auch der
                        // Konfigurations-Export zieht.
                        devicePolicyManager.transferOwnership(admin, transferTarget, PersistableBundle())
                        true
                    }
                },
            )
        }

        /** Rein lesend, s. Klassendoc-Kommentar an der Aufrufstelle in `onCreate()` — eine frische
         * [DeviceLockdownBundle]-Instanz ist hier bewusst ausreichend, weil `isActive()` per
         * Safeguard-Konvention nie zwischengespeicherten Zustand zurückgibt, sondern immer den
         * echten DPM-Zustand neu abfragt (`CompositeSafeguard.isActive()`: nur `true`, wenn
         * *alle* Mitglieder aktiv sind). `runCatching` statt Weiterwerfen — ein Lesefehler
         * (z. B. kein Device Owner) darf nicht als "inaktiv" durchgehen. */
        private fun isLockdownModeActive(context: Context): Boolean? =
            runCatching { DeviceLockdownBundle.build(context).isActive() }.getOrNull()
    }
}

/** "Lockdown-Auslöse-Profil" (2026-08-27) — Kühlzeit nach Bestätigungstext-Match, nur unter
 * [de.ble1st.warden.domain.pin.LockdownTriggerProfile.STRICT] und nur für
 * [SensitiveAction.LOCKDOWN_TASK_ENGAGE] wirksam, s. `SensitiveActionScreen`. */
private const val STRICT_LOCKDOWN_COOLDOWN_MILLIS = 3_000L
private const val COOLDOWN_TICK_MILLIS = 200L

private fun describeAction(context: Context, action: SensitiveAction): String = when (action) {
    SensitiveAction.WIPE_DATA -> context.getString(R.string.sensitive_action_describe_wipe_data)
    SensitiveAction.REBOOT -> context.getString(R.string.sensitive_action_describe_reboot)
    SensitiveAction.MASTER_SWITCH_REVERT -> context.getString(R.string.sensitive_action_describe_master_switch_revert)
    SensitiveAction.LOCK_NOW -> context.getString(R.string.sensitive_action_describe_lock_now)
    SensitiveAction.LOCKDOWN_MODE_ARM -> context.getString(R.string.sensitive_action_describe_lockdown_mode_arm)
    SensitiveAction.LOCKDOWN_TASK_ENGAGE -> context.getString(R.string.sensitive_action_describe_lockdown_task_engage)
    SensitiveAction.TRANSFER_OWNERSHIP -> context.getString(R.string.sensitive_action_describe_transfer_ownership)
}

/** Befund Q-5 (2026-08-29): nimmt jetzt [SensitiveActionOutcome] statt der reinen
 * Vorab-Entscheidung entgegen — sonst zeigte ein real fehlgeschlagenes `reboot()`/
 * `MasterSwitch.disarm()`/… hier trotzdem "✓ real ausgeführt", weil der alte Rückgabewert
 * ([SensitiveActionDecisionResult]) die Ausführung selbst gar nicht kannte. */
private fun describeOutcome(context: Context, action: SensitiveAction, outcome: SensitiveActionOutcome): String = when (outcome) {
    is SensitiveActionOutcome.Denied -> describeDeniedReason(context, outcome.reason)
    SensitiveActionOutcome.ExecutedSuccessfully -> context.getString(R.string.kiosk_outcome_executed_successfully)
    is SensitiveActionOutcome.ExecutedWithError -> context.getString(R.string.kiosk_outcome_executed_with_error, outcome.detail)
    SensitiveActionOutcome.ExecutedAsStub -> context.getString(R.string.sensitive_action_outcome_executed_as_stub)
}

private fun describeDeniedReason(context: Context, reason: SensitiveActionDecisionResult): String = when (reason) {
    SensitiveActionDecisionResult.ExecutionBlocked -> context.getString(R.string.kiosk_outcome_execution_blocked)
    SensitiveActionDecisionResult.RateLimited -> context.getString(R.string.kiosk_outcome_rate_limited)
    SensitiveActionDecisionResult.WrongConfirmationText -> context.getString(R.string.sensitive_action_denied_wrong_confirmation_text)
    SensitiveActionDecisionResult.PresenceNotProven -> context.getString(R.string.kiosk_outcome_presence_not_proven)
    SensitiveActionDecisionResult.Approved ->
        error("SensitiveActionOutcome.Denied wird nie mit reason=Approved erzeugt, s. dessen Klassendoc")
}

@Composable
private fun SensitiveActionScreen(
    executionAllowed: Boolean,
    checkLockdownActive: () -> Boolean?,
    sessionAuthenticated: Boolean,
    initialAction: SensitiveAction,
    lockdownTriggerProfile: LockdownTriggerProfile,
    transferTarget: ComponentName?,
    onConfirmSession: (SensitiveAction, String, (String) -> Unit) -> Unit,
    onConfirmBiometric: (SensitiveAction, String, (String) -> Unit) -> Unit,
    onConfirmPin: (SensitiveAction, String, (String) -> Unit) -> Unit,
) {
    var selectedAction by remember { mutableStateOf(initialAction) }
    var confirmationText by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    // "Arbeite langsam am Lockdownmodus", zweiter Schritt: rein informative Statuszeile, kein
    // Schalter (Nutzerwunsch: presence-gated, kein einfacher Ein/Aus wie ein Safeguard). Einmal
    // bei Eintritt gelesen, danach nach jeder abgeschlossenen Aktion neu abgefragt (s. unten) —
    // deckt sowohl "Lockdown gerade selbst scharf/zurückgesetzt" als auch "über Masterschalter
    // zurückgesetzt" in derselben Zeile ab.
    var lockdownActive by remember { mutableStateOf(checkLockdownActive()) }
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = stringResource(R.string.sensitive_action_screen_title), style = MaterialTheme.typography.headlineSmall)
            if (!executionAllowed) {
                Text(
                    text = stringResource(R.string.sensitive_action_debug_build_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = String.format(
                    stringResource(R.string.sensitive_action_lockdown_status_prefix),
                    when (lockdownActive) {
                        true -> stringResource(R.string.sensitive_action_lockdown_status_active)
                        false -> stringResource(R.string.sensitive_action_lockdown_status_inactive)
                        null -> stringResource(R.string.sensitive_action_lockdown_status_unknown)
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (lockdownActive == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Tier 3 (2026-09-05): TRANSFER_OWNERSHIP erscheint nur, wenn dieser Bildschirm mit
            // einem konkreten Ziel geöffnet wurde (aus den erweiterten Einstellungen heraus). Ohne
            // Ziel wäre die Zeile eine Aktion, die garantiert scheitert — und die gefährlichste
            // von allen ausgerechnet als Dauergast in der Liste stehen zu lassen, aus der man sonst
            // "Gerät neu starten" wählt, wäre genau die Art Stolperfalle, die dieser Bildschirm
            // mit Bestätigungstext und Presence-Prüfung sonst überall vermeidet.
            val selectableActions = SensitiveAction.entries.filter {
                it != SensitiveAction.TRANSFER_OWNERSHIP || transferTarget != null
            }
            if (transferTarget != null) {
                Text(
                    text = stringResource(R.string.sensitive_action_transfer_target_banner, transferTarget.flattenToShortString()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Column(Modifier.selectableGroup()) {
                selectableActions.forEach { action ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = action == selectedAction,
                                onClick = {
                                    selectedAction = action
                                    confirmationText = ""
                                    statusMessage = ""
                                },
                            ),
                    ) {
                        RadioButton(selected = action == selectedAction, onClick = null)
                        Column {
                            Text(text = action.displayName)
                            Text(text = describeAction(context, action), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // analyse.md (2026-09-02, Hoch): "WIPE_DATA-UI nicht als ausführbar darstellen,
            // solange es ein Stub ist" — `action.displayName`/`describeAction` sagen das bereits
            // klein neben dem Radio-Button, dieser zusätzliche, fehlerfarbene Absatz macht es
            // unübersehbar genau an der Stelle, wo als Nächstes der Bestätigungstext eingetippt
            // würde — bevor der Nutzer den Rest des ohnehin identischen Presence-Ablaufs
            // durchläuft und ihn mit einer der fünf echten Aktionen verwechseln könnte.
            if (selectedAction == SensitiveAction.WIPE_DATA) {
                Text(
                    text = stringResource(R.string.sensitive_action_wipe_data_stub_banner),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            OutlinedTextField(
                value = confirmationText,
                onValueChange = { confirmationText = it },
                label = { Text(stringResource(R.string.sensitive_action_confirmation_text_label)) },
                supportingText = { Text(String.format(stringResource(R.string.sensitive_action_confirmation_text_supporting), selectedAction.confirmationPhrase)) },
                modifier = Modifier.fillMaxWidth(),
            )

            // Bewusst NICHT zusätzlich auf `executionAllowed` gegatet: der Debug-Build-Hardblock
            // (F.4) greift ohnehin unbedingt tief in DestructiveActionExecutor — ihn zusätzlich
            // hier vor dem Klick zu prüfen, würde auf einem Debug-Build-Gerät beide Buttons
            // dauerhaft ausgrauen und den gesamten Bestätigungs-/Presence-Ablauf unantastbar
            // machen, nicht nur die eigentliche Aktionsausführung. Die Warnung oben bleibt
            // sichtbar, das Ergebnis zeigt korrekt "ExecutionBlocked" (`describeDecision`).
            val confirmEnabled = confirmationText == selectedAction.confirmationPhrase
            // "Lockdown-Auslöse-Profil" (2026-08-27): unter LockdownTriggerProfile.STRICT bleibt
            // für LOCKDOWN_TASK_ENGAGE der Session-Presence-Kurzweg unten strukturell deaktiviert,
            // obwohl SensitiveAction.allowsSessionPresence weiterhin true ist (die Enum selbst
            // bleibt unangetastet — dieselbe lokale Override-Haltung wie überall sonst in diesem
            // Screen: die Entscheidung wird hier ausgewertet, nicht am Enum geändert). Dazu eine
            // kurze Kühlzeit nach Phrasen-Match, bevor die verbleibenden zwei Presence-Buttons
            // überhaupt antippbar werden — verhindert einen Reflex-Tap direkt nach dem Tippen.
            val forcedFullPresence = selectedAction == SensitiveAction.LOCKDOWN_TASK_ENGAGE &&
                !LockdownTriggerProfilePolicy.allowSessionPresenceReuse(lockdownTriggerProfile)
            var cooldownRemainingMillis by remember(selectedAction) { mutableStateOf(0L) }
            LaunchedEffect(confirmEnabled, selectedAction) {
                if (forcedFullPresence && confirmEnabled) {
                    var remaining = STRICT_LOCKDOWN_COOLDOWN_MILLIS
                    cooldownRemainingMillis = remaining
                    while (remaining > 0) {
                        delay(COOLDOWN_TICK_MILLIS)
                        remaining = (remaining - COOLDOWN_TICK_MILLIS).coerceAtLeast(0)
                        cooldownRemainingMillis = remaining
                    }
                } else {
                    cooldownRemainingMillis = 0L
                }
            }
            val presenceButtonsEnabled = confirmEnabled && cooldownRemainingMillis <= 0
            // WardenLock (Finalisierungsphase 2026-08-24, auf Nutzerwunsch): für die vier
            // session-fähigen Aktionen genügt der beim App-Eintritt bereits erbrachte Nachweis —
            // kein zweiter Biometrie-/PIN-Prompt hier. `WIPE_DATA` (`allowsSessionPresence=false`)
            // behält den bisherigen Zwei-Wege-Presence-Flow unverändert, s. `SensitiveAction`-
            // Klassendoc.
            if (selectedAction.allowsSessionPresence && sessionAuthenticated && !forcedFullPresence) {
                TextButton(
                    enabled = confirmEnabled,
                    onClick = {
                        onConfirmSession(selectedAction, confirmationText) { message ->
                            statusMessage = message
                            lockdownActive = checkLockdownActive()
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            } else {
                if (forcedFullPresence && cooldownRemainingMillis > 0) {
                    Text(
                        text = String.format(stringResource(R.string.sensitive_action_strict_cooldown_message), cooldownRemainingMillis / 1000.0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    enabled = presenceButtonsEnabled,
                    onClick = {
                        onConfirmBiometric(selectedAction, confirmationText) { message ->
                            statusMessage = message
                            lockdownActive = checkLockdownActive()
                        }
                    },
                ) {
                    Text(stringResource(R.string.sensitive_action_confirm_biometric_action))
                }

                // Presence-Reaktivierung (s. Klassendoc): zweiter, gleichrangiger Presence-Weg für
                // Geräte ohne Class-3-Sensor — Threat Model T4 sieht beide von Anfang an als
                // gleichwertige Alternativen vor, kein "Fallback zweiter Klasse".
                TextButton(
                    enabled = presenceButtonsEnabled,
                    onClick = {
                        onConfirmPin(selectedAction, confirmationText) { message ->
                            statusMessage = message
                            lockdownActive = checkLockdownActive()
                        }
                    },
                ) {
                    Text(stringResource(R.string.sensitive_action_confirm_pin_action))
                }
            }

            if (statusMessage.isNotEmpty()) {
                Text(text = statusMessage, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
