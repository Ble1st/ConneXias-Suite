package de.ble1st.warden.presence

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import de.ble1st.warden.BuildConfig
import de.ble1st.warden.WardenApplication
import de.ble1st.warden.admin.WardenDeviceAdminReceiver
import de.ble1st.warden.domain.presence.DestructiveCommandGuard
import de.ble1st.warden.domain.presence.SensitiveAction
import de.ble1st.warden.domain.presence.SensitiveActionDecisionResult
import de.ble1st.warden.domain.registry.SafeguardRegistry
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
 * **`REBOOT`/`MASTER_SWITCH_REVERT`/`LOCK_NOW`/`LOCKDOWN_MODE_ARM` real verkabelt, `WIPE_DATA`
 * bewusst weiterhin Stub** ([DestructiveActionExecutor]-Klassendoc für die Begründung).
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

        val executor = buildExecutor(applicationContext)
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

        setContent {
            WardenTheme(accent = accent) {
                SensitiveActionScreen(
                    executionAllowed = executionAllowed,
                    checkLockdownActive = checkLockdownActive,
                    sessionAuthenticated = wardenLockSession.isAuthenticated(),
                    onConfirmSession = { action, confirmationText, onResult ->
                        val decision = executor.executeWithSessionPresence(
                            action,
                            confirmationText,
                            sessionAuthenticated = wardenLockSession.isAuthenticated(),
                        )
                        onResult(describeDecision(action, decision))
                    },
                    onConfirmBiometric = { action, confirmationText, onResult ->
                        presenceManager.request(
                            title = "Sensible Aktion bestätigen",
                            subtitle = describeAction(action),
                        ) { result ->
                            when (result) {
                                is PresenceManager.Result.Success -> {
                                    val decision = executor.execute(action, confirmationText, result.proof)
                                    onResult(describeDecision(action, decision))
                                }
                                PresenceManager.Result.Unavailable ->
                                    onResult("⚠ Keine Biometrie eingerichtet — Aktion nicht möglich.")
                                PresenceManager.Result.Cancelled ->
                                    onResult("Abgebrochen.")
                            }
                        }
                    },
                    onConfirmPin = { action, confirmationText, onResult ->
                        pendingPinPresenceResult = { granted ->
                            val decision = executor.executeWithPinPresence(action, confirmationText, granted)
                            onResult(describeDecision(action, decision))
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
        /** Verkabelt Presence-/Rate-Limit-/Bestätigungs-Kette mit den echten
         * `DevicePolicyManager`-Aufrufen für `REBOOT` und `MasterSwitch.disarm()` für
         * `MASTER_SWITCH_REVERT` — dieselben drei bekannten C.2-Schalter wie `FailsafeActivity`
         * und `RegistryReconciliationReceiver`. `WIPE_DATA` bleibt Stub (s. Klassendoc/
         * [DestructiveActionExecutor]). */
        private fun buildExecutor(context: Context): DestructiveActionExecutor {
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

private fun describeAction(action: SensitiveAction): String = when (action) {
    SensitiveAction.WIPE_DATA -> "Nur Protokoll — wipeData() bleibt bewusst unverkabelt"
    SensitiveAction.REBOOT -> "Startet das Gerät neu, wenn die Debug-Build-Sperre nicht greift"
    SensitiveAction.MASTER_SWITCH_REVERT -> "Setzt alle Safeguards inklusive Lockdown zurück"
    SensitiveAction.LOCK_NOW -> "Sperrt das Gerät sofort"
    SensitiveAction.LOCKDOWN_MODE_ARM ->
        "USB aus, Safe Boot/Werksreset/OEM-Unlock/Debugging blockiert. Rückweg: Alle Safeguards zurücksetzen."
}

private fun describeDecision(action: SensitiveAction, decision: SensitiveActionDecisionResult): String = when (decision) {
    SensitiveActionDecisionResult.Approved -> if (action == SensitiveAction.WIPE_DATA) {
        "✓ Bestätigt — Stub protokolliert (wipeData() bewusst weiterhin nicht verkabelt)."
    } else {
        "✓ Bestätigt — real ausgeführt und protokolliert."
    }
    SensitiveActionDecisionResult.ExecutionBlocked -> "⚠ Debug-Build — destruktive Kommandos hart abgeschaltet (F.4)."
    SensitiveActionDecisionResult.RateLimited -> "⚠ Zu viele Versuche — bitte kurz warten."
    SensitiveActionDecisionResult.WrongConfirmationText -> "⚠ Bestätigungstext stimmte nicht."
    SensitiveActionDecisionResult.PresenceNotProven -> "⚠ Presence-Nachweis fehlgeschlagen."
}

@Composable
private fun SensitiveActionScreen(
    executionAllowed: Boolean,
    checkLockdownActive: () -> Boolean?,
    sessionAuthenticated: Boolean,
    onConfirmSession: (SensitiveAction, String, (String) -> Unit) -> Unit,
    onConfirmBiometric: (SensitiveAction, String, (String) -> Unit) -> Unit,
    onConfirmPin: (SensitiveAction, String, (String) -> Unit) -> Unit,
) {
    var selectedAction by remember { mutableStateOf(SensitiveAction.MASTER_SWITCH_REVERT) }
    var confirmationText by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    // "Arbeite langsam am Lockdownmodus", zweiter Schritt: rein informative Statuszeile, kein
    // Schalter (Nutzerwunsch: presence-gated, kein einfacher Ein/Aus wie ein Safeguard). Einmal
    // bei Eintritt gelesen, danach nach jeder abgeschlossenen Aktion neu abgefragt (s. unten) —
    // deckt sowohl "Lockdown gerade selbst scharf/zurückgesetzt" als auch "über Masterschalter
    // zurückgesetzt" in derselben Zeile ab.
    var lockdownActive by remember { mutableStateOf(checkLockdownActive()) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Sensible Aktion", style = MaterialTheme.typography.headlineSmall)
            if (!executionAllowed) {
                Text(
                    text = "⚠ Debug-Build — destruktive Kommandos sind hart abgeschaltet (Konzept F.4).",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "Lockdown-Modus: " + when (lockdownActive) {
                    true -> "AKTIV"
                    false -> "inaktiv"
                    null -> "unbekannt (Lesen fehlgeschlagen)"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (lockdownActive == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(Modifier.selectableGroup()) {
                SensitiveAction.entries.forEach { action ->
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
                            Text(text = describeAction(action), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = confirmationText,
                onValueChange = { confirmationText = it },
                label = { Text("Bestätigungstext exakt eintippen") },
                supportingText = { Text("Tippe ${selectedAction.confirmationPhrase}") },
                modifier = Modifier.fillMaxWidth(),
            )

            // Bewusst NICHT zusätzlich auf `executionAllowed` gegatet: der Debug-Build-Hardblock
            // (F.4) greift ohnehin unbedingt tief in DestructiveActionExecutor — ihn zusätzlich
            // hier vor dem Klick zu prüfen, würde auf einem Debug-Build-Gerät beide Buttons
            // dauerhaft ausgrauen und den gesamten Bestätigungs-/Presence-Ablauf unantastbar
            // machen, nicht nur die eigentliche Aktionsausführung. Die Warnung oben bleibt
            // sichtbar, das Ergebnis zeigt korrekt "ExecutionBlocked" (`describeDecision`).
            val confirmEnabled = confirmationText == selectedAction.confirmationPhrase
            // WardenLock (Finalisierungsphase 2026-08-24, auf Nutzerwunsch): für die vier
            // session-fähigen Aktionen genügt der beim App-Eintritt bereits erbrachte Nachweis —
            // kein zweiter Biometrie-/PIN-Prompt hier. `WIPE_DATA` (`allowsSessionPresence=false`)
            // behält den bisherigen Zwei-Wege-Presence-Flow unverändert, s. `SensitiveAction`-
            // Klassendoc.
            if (selectedAction.allowsSessionPresence && sessionAuthenticated) {
                TextButton(
                    enabled = confirmEnabled,
                    onClick = {
                        onConfirmSession(selectedAction, confirmationText) { message ->
                            statusMessage = message
                            lockdownActive = checkLockdownActive()
                        }
                    },
                ) {
                    Text("Bestätigen")
                }
            } else {
                TextButton(
                    enabled = confirmEnabled,
                    onClick = {
                        onConfirmBiometric(selectedAction, confirmationText) { message ->
                            statusMessage = message
                            lockdownActive = checkLockdownActive()
                        }
                    },
                ) {
                    Text("Mit Biometrie bestätigen")
                }

                // Presence-Reaktivierung (s. Klassendoc): zweiter, gleichrangiger Presence-Weg für
                // Geräte ohne Class-3-Sensor — Threat Model T4 sieht beide von Anfang an als
                // gleichwertige Alternativen vor, kein "Fallback zweiter Klasse".
                TextButton(
                    enabled = confirmEnabled,
                    onClick = {
                        onConfirmPin(selectedAction, confirmationText) { message ->
                            statusMessage = message
                            lockdownActive = checkLockdownActive()
                        }
                    },
                ) {
                    Text("Mit Warden-PIN bestätigen")
                }
            }

            if (statusMessage.isNotEmpty()) {
                Text(text = statusMessage, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
