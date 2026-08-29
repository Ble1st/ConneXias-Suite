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
 * nur, wenn Warden das Notruf-Drill-Bit mitgegeben hat **und** hier lokal eine benutzbare
 * Sentinel-PIN vorliegt (`SentinelLockTaskGate`, zweite Bedingung seit 2026-08-28 — s. dortiges
 * Klassendoc für den Fund).
 *
 * **Verweigertes Scharfschalten wird zurückgemeldet** ([signalWardenEngageRefused], seit
 * 2026-08-28): sonst hätte Warden seinen Watchdog scharf und Sentinels Paket auf der
 * DPM-Lock-Task-Whitelist, während faktisch kein Kiosk läuft — und würde in
 * `DestructiveActionExecutor` "real angestoßen" protokollieren. `SentinelLockdownEngager.engage()`
 * kann das nicht selbst merken: es fängt nur `ActivityNotFoundException` ab, ein *gestarteter*,
 * aber ablehnender Sentinel sieht von dort aus wie ein Erfolg aus. Derselbe `signature`-geschützte
 * Kanal wie das Entwarn-Signal, nur mit eigener Action.
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
                        onExitLockdown = { exitLockdown(ACTION_PIN_VERIFIED) },
                        onAbandonCorruptedLockdown = { exitLockdown(ACTION_ENGAGE_REFUSED) },
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
        // Vorschlag U-8 (2026-08-29): Warden kann Sentinels PIN-Zustand nicht selbst lesen
        // (eigene UID, eigener credential-verschlüsselter Speicher) — bis hierher merkte es erst
        // im Ernstfall, dass die eine Bedingung fehlt, ohne die der Kiosk wirkungslos ist. Deshalb
        // meldet Sentinel den Zustand jetzt von sich aus, über denselben signature-geschützten
        // Kanal wie die beiden anderen Signale.
        signalWardenPinState()
        if (lockdownEngageTriggered) return
        val requested = intent?.getBooleanExtra(EXTRA_ENGAGE_LOCKDOWN, false) == true
        if (!requested) return
        lockdownEngageTriggered = true
        val drillPassed = intent?.getBooleanExtra(EXTRA_EMERGENCY_CALL_DRILL_PASSED, false) == true
        // Frisch gelesen, nicht aus dem Compose-State übernommen: zwischen dem Aufbau des
        // Bildschirms und diesem Aufruf kann eine Ersteinrichtung liegen (derselbe "nie ein
        // potenziell veraltetes Bit cachen"-Vorbehalt wie überall im Projekt).
        val pinConfigured = isPinConfigured()
        val started = SentinelLockTaskManager(this).startIfPermitted(
            emergencyCallDrillPassed = drillPassed,
            pinConfigured = pinConfigured,
        )
        if (started) {
            Log.w(TAG, "Scharfschalten angefordert: startLockTask() ausgelöst")
            return
        }
        // Kein stiller Fehlschlag: Warden hält an dieser Stelle bereits Watchdog + DPM-Whitelist
        // scharf und würde ohne diese Rückmeldung einen Kiosk protokollieren, den es nicht gibt.
        val reason = when {
            !drillPassed -> "Notruf-Drill nicht bestätigt"
            !pinConfigured -> "keine benutzbare Sentinel-PIN eingerichtet"
            else -> "DPM-Lock-Task-Whitelist fehlt"
        }
        Log.w(TAG, "Scharfschalten angefordert: startLockTask() abgelehnt — $reason")
        signalWardenEngageRefused(reason)
    }

    /** Zweiter Meldezeitpunkt neben [onResume] (Vorschlag U-8): der übliche Ablauf einer
     * Ersteinrichtung ist "Sentinel öffnen → PIN setzen → verlassen", und dabei kommt kein
     * weiteres `onResume()` mehr. Ohne diesen Aufruf meldete Sentinel Warden also genau den
     * Zustand *vor* der Einrichtung und Warden zeigte dauerhaft "PIN fehlt". Ein zusätzlicher
     * Callback aus dem Compose-Bildschirm heraus wäre die präzisere, aber auch die deutlich
     * verdrahtungsintensivere Lösung — `onPause()` fasst jede denkbare PIN-Änderung mit ab. */
    override fun onPause() {
        super.onPause()
        signalWardenPinState()
    }

    /**
     * Nur ein *verifizierbarer* PIN-Blob zählt. [SentinelPinStateDecision.LoadResult.Corrupted]
     * gilt hier bewusst als "nicht eingerichtet" — s. [SentinelLockTaskGate]-Klassendoc: mit einem
     * unlesbaren Blob gäbe es keinen Ausstieg mehr aus dem Kiosk.
     */
    private fun isPinConfigured(): Boolean {
        val pinStore = SentinelPinStorage.openStore(applicationContext)
        val result = SentinelPinStateDecision.load(pinStore.exists()) { pinStore.load() }
        return result is SentinelPinStateDecision.LoadResult.Loaded
    }

    /** Gemeinsamer Ausstieg für beide Wege (korrekte PIN und aufgegebener Kiosk bei beschädigtem
     * Blob): erst Warden informieren, dann **unabhängig vom Broadcast-Erfolg** lokal
     * `stopLockTask()` — s. Klassendoc, der lokale Ausweg darf nie von Wardens Erreichbarkeit
     * abhängen. */
    private fun exitLockdown(action: String) {
        runCatching {
            sendBroadcast(
                Intent().setClassName(WARDEN_PACKAGE_NAME, WARDEN_SIGNAL_RECEIVER_CLASS_NAME)
                    .setAction(action),
            )
        }.onFailure { Log.w(TAG, "Signal an Warden fehlgeschlagen (Warden nicht erreichbar?)", it) }
        runCatching { SentinelLockTaskManager(this).stop() }
            .onFailure { Log.e(TAG, "stopLockTask() fehlgeschlagen", it) }
    }

    private fun signalWardenEngageRefused(reason: String) {
        runCatching {
            sendBroadcast(
                Intent().setClassName(WARDEN_PACKAGE_NAME, WARDEN_SIGNAL_RECEIVER_CLASS_NAME)
                    .setAction(ACTION_ENGAGE_REFUSED)
                    .putExtra(EXTRA_REFUSAL_REASON, reason),
            )
        }.onFailure { Log.w(TAG, "Ablehnungs-Signal an Warden fehlgeschlagen", it) }
    }

    /** Meldet Warden, ob hier eine benutzbare PIN liegt (Vorschlag U-8). Bewusst *nur* dieses eine
     * Bit — kein Hash, keine Länge, kein Zeitstempel: Warden braucht für die Anzeige der
     * Kiosk-Vorbedingung nicht mehr, und alles darüber hinaus wäre über einen Broadcast
     * verschickte PIN-Information ohne Gegenwert. Fehlschläge werden nur geloggt: Warden kann
     * deinstalliert oder deaktiviert sein, und Sentinels eigene Funktion hängt daran nicht. */
    private fun signalWardenPinState() {
        val configured = runCatching { isPinConfigured() }.getOrElse {
            Log.w(TAG, "PIN-Zustand nicht lesbar — keine Meldung an Warden", it)
            return
        }
        runCatching {
            sendBroadcast(
                Intent().setClassName(WARDEN_PACKAGE_NAME, WARDEN_SIGNAL_RECEIVER_CLASS_NAME)
                    .setAction(ACTION_PIN_STATE)
                    .putExtra(EXTRA_PIN_CONFIGURED, configured),
            )
        }.onFailure { Log.w(TAG, "PIN-Zustands-Signal an Warden fehlgeschlagen", it) }
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

        /** Zweite Action auf demselben `signature`-geschützten Kanal (2026-08-28): Sentinel hat
         * das Scharfschalten abgelehnt oder einen unverlassbaren Kiosk aufgegeben. Warden
         * behandelt beides gleich — Watchdog entschärfen, Whitelist zurückziehen, laut
         * protokollieren. */
        const val ACTION_ENGAGE_REFUSED = "de.ble1st.warden.sentinel.action.ENGAGE_REFUSED"

        const val EXTRA_REFUSAL_REASON = "refusalReason"

        /** Dritte Action auf demselben Kanal (Vorschlag U-8, 2026-08-29): unaufgeforderte Meldung
         * des PIN-Zustands, damit Warden die Kiosk-Vorbedingung *vorher* anzeigen kann und nicht
         * erst über [ACTION_ENGAGE_REFUSED] im Ernstfall davon erfährt. Anders als die beiden
         * anderen Actions darf sie den Watchdog **nicht** entschärfen — sie sagt nichts über einen
         * laufenden Kiosk aus. */
        const val ACTION_PIN_STATE = "de.ble1st.warden.sentinel.action.PIN_STATE"

        const val EXTRA_PIN_CONFIGURED = "pinConfigured"

        const val TAG = "Sentinel"
    }
}

@Composable
private fun SentinelScreen(
    pinStore: SentinelPinStore,
    isLockTaskActive: () -> Boolean,
    onExitLockdown: () -> Unit,
    onAbandonCorruptedLockdown: () -> Unit,
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

                // Bis 2026-08-28 stand hier nur der Warntext — ohne Tastenfeld und ohne Ausstieg.
                // Lief in dem Moment ein Lock-Task, war das Gerät endgültig gefangen: die PIN ist
                // nicht mehr verifizierbar, Warden ist im Kiosk nicht erreichbar, und
                // EMERGENCY_PRESERVING_FEATURES lässt nur Keyguard und Notruf offen — es blieb der
                // Werksreset. Der Knopf unten wiegt diesen Totalverlust gegen den Umstand ab, dass
                // ein beschädigter Blob ohnehin niemanden mehr aussperrt: wer ihn manipulieren
                // könnte, bräuchte Schreibzugriff auf Sentinels eigene UID und hätte damit längst
                // mehr Möglichkeiten als diesen Knopf. Das Gate verhindert seit demselben Tag, dass
                // dieser Zustand überhaupt noch neu betreten wird (s. SentinelLockTaskGate).
                is SentinelPinStateDecision.LoadResult.Corrupted -> {
                    Text(
                        text = "⚠ Zustand beschädigt — die Sentinel-PIN ist nicht mehr prüfbar.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB3261E),
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    if (isLockTaskActive()) {
                        Text(
                            text = "Der Kiosk lässt sich ohne prüfbare PIN nicht regulär verlassen. " +
                                "Beenden und in Warden eine neue Sentinel-PIN einrichten.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Button(
                            onClick = onAbandonCorruptedLockdown,
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            Text("Kiosk beenden")
                        }
                    }
                }
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
