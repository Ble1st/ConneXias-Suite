package de.ble1st.warden.sentinel

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.ble1st.warden.sentinel.crypto.Engine
import de.ble1st.warden.sentinel.domain.SentinelAntiHammeringDecision
import de.ble1st.warden.sentinel.domain.SentinelPinBlob
import de.ble1st.warden.sentinel.domain.SentinelPinDecision
import de.ble1st.warden.sentinel.domain.SentinelPinDecisionResult
import de.ble1st.warden.sentinel.domain.SentinelPinStateDecision
import de.ble1st.warden.sentinel.pin.SentinelPinStorage
import de.ble1st.warden.sentinel.pin.SentinelPinStore
import java.nio.charset.StandardCharsets
import uniffi.connexias_engine.PasswordHash

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 8

/**
 * Sentinels einziger Bildschirm — ein PIN-Numpad, sonst nichts (Plan-Kontext: kleinstmögliche
 * Angriffs-/Fehlerfläche für den Lockdown-Modus-Kiosk-Zustand, statt Wardens gesamte Dashboard-UI
 * einzusperren). Kein Launcher-Eintrag (AndroidManifest.xml) — Warden startet diese Activity
 * ausschließlich explizit per `ComponentName`
 * (`de.ble1st.warden.presence.SentinelLockdownEngager`), geschützt durch die
 * `de.ble1st.warden.sentinel.permission.ENGAGE`-`signature`-Permission (nur ein mit demselben
 * Zertifikat signiertes Paket darf das).
 *
 * **Scharfschalten:** [onResume] liest [EXTRA_ENGAGE_LOCKDOWN]/[EXTRA_EMERGENCY_CALL_DRILL_PASSED]
 * und ruft bei `true` [SentinelLockTaskManager.startIfPermitted] — echtes `startLockTask()` läuft
 * nur, wenn Warden das Notruf-Drill-Bit mitgegeben hat (`SentinelLockTaskGate`).
 *
 * **Entsperren:** bei korrekter PIN **während aktivem Lock-Task**
 * ([ActivityManager.getLockTaskModeState] live abgefragt, kein zwischengespeichertes lokales
 * Flag — robust gegen einen zwischenzeitlichen Prozess-Neustart) sendet [signalWardenPinVerified]
 * einen `signature`-geschützten Broadcast an Wardens `SentinelSignalReceiver` und ruft
 * **unabhängig vom Broadcast-Erfolg** lokal `stopLockTask()` — der lokale Ausstieg darf nie davon
 * abhängen, dass Warden erreichbar ist (Fail-Safe: ein deinstalliertes/abgestürztes Warden darf
 * den einzigen Ausweg aus dem Kiosk nicht blockieren).
 */
class SentinelActivity : ComponentActivity() {

    private var lockdownEngageTriggered = false
    private var intentGeneration by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val pinStore = SentinelPinStorage.openStore(applicationContext)

        setContent {
            MaterialTheme {
                key(intentGeneration) {
                    SentinelScreen(
                        pinStore = pinStore,
                        isLockTaskActive = ::isLockTaskActive,
                        onExitLockdown = {
                            runCatching {
                                sendBroadcast(
                                    Intent().setClassName(WARDEN_PACKAGE_NAME, WARDEN_SIGNAL_RECEIVER_CLASS_NAME)
                                        .setAction(ACTION_PIN_VERIFIED),
                                )
                            }.onFailure { Log.w(TAG, "Entwarn-Signal an Warden fehlgeschlagen (Warden nicht erreichbar?)", it) }
                            runCatching { SentinelLockTaskManager(this@SentinelActivity).stop() }
                                .onFailure { Log.e(TAG, "stopLockTask() fehlgeschlagen", it) }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lockdownEngageTriggered = false
        intentGeneration++
    }

    /** `startLockTask()` soll laut Android-Dokumentation aufgerufen werden, wenn die Activity
     * bereits resumed ist, nicht schon in `onCreate()`. */
    override fun onResume() {
        super.onResume()
        if (lockdownEngageTriggered) return
        val requested = intent?.getBooleanExtra(EXTRA_ENGAGE_LOCKDOWN, false) == true
        if (!requested) return
        lockdownEngageTriggered = true
        val drillPassed = intent?.getBooleanExtra(EXTRA_EMERGENCY_CALL_DRILL_PASSED, false) == true
        val started = SentinelLockTaskManager(this).startIfPermitted(emergencyCallDrillPassed = drillPassed)
        Log.w(TAG, "Scharfschalten angefordert: startLockTask() ${if (started) "ausgelöst" else "abgelehnt (Gate/DPM-Whitelist)"}")
    }

    /** Live abgefragt statt eines lokalen Flags — robust gegen einen zwischenzeitlichen
     * Prozess-Neustart (Sentinels eigener Prozess kann vom System beendet und beim nächsten
     * PIN-Versuch neu gestartet werden, während Lock-Task auf DPM-Ebene weiterhin aktiv bleibt). */
    private fun isLockTaskActive(): Boolean {
        val am = getSystemService(ActivityManager::class.java) ?: return false
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    companion object {
        /** Von `de.ble1st.warden.presence.SentinelLockdownEngager` gesetzt. */
        const val EXTRA_ENGAGE_LOCKDOWN = "engageLockdown"
        const val EXTRA_EMERGENCY_CALL_DRILL_PASSED = "emergencyCallDrillPassed"

        private const val WARDEN_PACKAGE_NAME = "de.ble1st.warden"
        private const val WARDEN_SIGNAL_RECEIVER_CLASS_NAME = "de.ble1st.warden.sentinelbridge.SentinelSignalReceiver"

        /** Muss mit `de.ble1st.warden.sentinelbridge.SentinelSignalReceiver.ACTION_PIN_VERIFIED`
         * übereinstimmen (kein gemeinsames Modul für diese eine Konstante — bewusst, s. Plan). */
        const val ACTION_PIN_VERIFIED = "de.ble1st.warden.sentinel.action.PIN_VERIFIED"

        const val TAG = "Sentinel"
    }
}

@Composable
private fun SentinelScreen(
    pinStore: SentinelPinStore,
    isLockTaskActive: () -> Boolean,
    onExitLockdown: () -> Unit,
) {
    fun currentLoadResult() = SentinelPinStateDecision.load(pinStore.exists()) { pinStore.load() }

    var loadResult by remember { mutableStateOf(currentLoadResult()) }
    var pendingFirstPin by remember { mutableStateOf<List<Int>?>(null) }
    var digits by remember { mutableStateOf(emptyList<Int>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var unlocked by remember { mutableStateOf(false) }

    fun onDigit(d: Int) {
        message = null
        if (digits.size < MAX_PIN_LENGTH) digits = digits + d
    }

    fun onBackspace() {
        message = null
        if (digits.isNotEmpty()) digits = digits.dropLast(1)
    }

    fun onConfirmSetup() {
        if (digits.size < MIN_PIN_LENGTH) return
        val firstPin = pendingFirstPin
        when {
            firstPin == null -> {
                pendingFirstPin = digits
                message = "Jetzt zur Bestätigung erneut eingeben."
            }
            firstPin == digits -> {
                val pinBytes = digits.joinToString(separator = "").toByteArray(StandardCharsets.UTF_8)
                val hash = Engine.hashPassword(pinBytes)
                pinBytes.fill(0)
                pinStore.persistNewVersion { current: SentinelPinBlob ->
                    current.copy(pinHash = hash.phc, failedAttempts = 0, backoffUntilEpochSeconds = 0)
                }
                pendingFirstPin = null
                message = "PIN gespeichert."
                loadResult = currentLoadResult()
            }
            else -> {
                pendingFirstPin = null
                message = "PINs stimmten nicht überein — von vorn."
            }
        }
        digits = emptyList()
    }

    fun onConfirmVerify(blob: SentinelPinBlob) {
        if (digits.size < MIN_PIN_LENGTH) return
        val nowSeconds = System.currentTimeMillis() / 1000
        if (!SentinelAntiHammeringDecision.isAttemptAllowedNow(blob.backoffUntilEpochSeconds, nowSeconds)) {
            message = "Gesperrt — noch ${blob.backoffUntilEpochSeconds - nowSeconds}s warten."
            digits = emptyList()
            return
        }

        val pinBytes = digits.joinToString(separator = "").toByteArray(StandardCharsets.UTF_8)
        val result = SentinelPinDecision.evaluate(
            storedHash = blob.pinHash.ifEmpty { null },
            enteredPin = pinBytes,
            verify = { pin, hash -> Engine.verifyPassword(pin, PasswordHash(hash)) },
        )
        pinBytes.fill(0)

        when (result) {
            SentinelPinDecisionResult.Accepted -> {
                pinStore.persistNewVersion { current ->
                    current.copy(failedAttempts = 0, backoffUntilEpochSeconds = 0)
                }
                unlocked = true
                message = null
                // Nur signalisieren/aussperren, wenn tatsächlich ein Kiosk-Zustand aktiv ist —
                // eine PIN-Verifikation außerhalb von Lock-Task (z. B. direkt nach der
                // Ersteinrichtung) hat nichts zu beenden, s. Klassendoc.
                if (isLockTaskActive()) onExitLockdown()
            }
            SentinelPinDecisionResult.Rejected -> {
                val failedAttempts = blob.failedAttempts + 1
                val backoff = SentinelAntiHammeringDecision.backoffSecondsFor(failedAttempts)
                pinStore.persistNewVersion { current ->
                    current.copy(failedAttempts = failedAttempts, backoffUntilEpochSeconds = nowSeconds + backoff)
                }
                message = if (backoff > 0) "Falsche PIN — Sperre für ${backoff}s." else "Falsche PIN."
            }
            SentinelPinDecisionResult.NotConfigured -> {
                message = "Keine PIN eingerichtet."
            }
        }
        loadResult = currentLoadResult()
        digits = emptyList()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "Sentinel", style = MaterialTheme.typography.headlineMedium)

            when (val result = loadResult) {
                SentinelPinStateDecision.LoadResult.NotYetConfigured -> SetupContent(
                    subtitle = if (pendingFirstPin == null) "PIN festlegen" else "PIN zur Bestätigung erneut eingeben",
                    message = message,
                    digits = digits,
                    onDigit = ::onDigit,
                    onBackspace = ::onBackspace,
                    onConfirm = ::onConfirmSetup,
                )

                is SentinelPinStateDecision.LoadResult.Loaded -> if (unlocked) {
                    Text(text = "Entsperrt", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                } else {
                    val nowSeconds = System.currentTimeMillis() / 1000
                    val blocked = !SentinelAntiHammeringDecision.isAttemptAllowedNow(result.blob.backoffUntilEpochSeconds, nowSeconds)
                    SetupContent(
                        subtitle = "PIN eingeben",
                        message = message,
                        digits = digits,
                        onDigit = ::onDigit,
                        onBackspace = ::onBackspace,
                        onConfirm = { onConfirmVerify(result.blob) },
                        confirmExtraDisabled = blocked,
                    )
                }

                is SentinelPinStateDecision.LoadResult.Corrupted -> Text(
                    text = "⚠ Zustand beschädigt",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB3261E),
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SetupContent(
    subtitle: String,
    message: String?,
    digits: List<Int>,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    confirmExtraDisabled: Boolean = false,
) {
    Text(text = subtitle, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    message?.let {
        Text(text = it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    }
    PinDots(enteredLength = digits.size, maxLength = MAX_PIN_LENGTH, modifier = Modifier.padding(vertical = 24.dp))
    Numpad(
        onDigit = onDigit,
        onBackspace = onBackspace,
        onConfirm = onConfirm,
        confirmEnabled = digits.size >= MIN_PIN_LENGTH && !confirmExtraDisabled,
    )
}

@Composable
private fun PinDots(enteredLength: Int, maxLength: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics {
            contentDescription = "$enteredLength von $maxLength Ziffern eingegeben"
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(maxLength) { index ->
            val filled = index < enteredLength
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(
                        color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

private val NUMPAD_ROWS = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))

@Composable
private fun Numpad(
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        NUMPAD_ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { digit ->
                    NumpadButton(label = "$digit", description = "Ziffer $digit", onClick = { onDigit(digit) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumpadButton(label = "⌫", description = "Löschen", onClick = onBackspace)
            NumpadButton(label = "0", description = "Ziffer 0", onClick = { onDigit(0) })
            NumpadButton(label = "OK", description = "Bestätigen", onClick = onConfirm, enabled = confirmEnabled)
        }
    }
}

@Composable
private fun NumpadButton(label: String, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(72.dp)
            .semantics { contentDescription = description },
    ) {
        Text(text = label, style = MaterialTheme.typography.titleLarge)
    }
}
