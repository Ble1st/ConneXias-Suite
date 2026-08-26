package de.ble1st.warden.presence

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.ble1st.warden.BuildConfig
import de.ble1st.warden.WardenApplication
import de.ble1st.warden.crypto.Engine
import de.ble1st.warden.failsafe.FailsafeActivity
import de.ble1st.warden.domain.pin.WardenAntiHammeringDecision
import de.ble1st.warden.domain.pin.WardenPinBlob
import de.ble1st.warden.domain.pin.WardenPinDecision
import de.ble1st.warden.domain.pin.WardenPinDecisionResult
import de.ble1st.warden.domain.pin.WardenPinStateDecision
import de.ble1st.warden.logging.HashChainLogStore
import de.ble1st.warden.wardenAuditLog
import de.ble1st.warden.pin.WardenDuressPinStorage
import de.ble1st.warden.pin.WardenPinStorage
import de.ble1st.warden.pin.WardenPinStore
import de.ble1st.warden.sentinelbridge.SentinelLockdownEngager
import de.ble1st.warden.ui.theme.WardenTheme
import de.ble1st.warden.ui.theme.WardenThemePrefs
import java.nio.charset.StandardCharsets
import uniffi.connexias_engine.PasswordHash

private const val MIN_PIN_LENGTH = 6
/** Existing 4-digit PINs must still be enterable; new / changed PINs use [MIN_PIN_LENGTH]. */
private const val UNLOCK_MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 8

/** Formen für die verbundene "PIN ändern"/"Duress-PIN"-Button-Gruppe, s. Kommentar an der
 * Verwendungsstelle in [WardenPinScreen]. */
private val ButtonGroupStartShape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 4.dp, bottomEnd = 4.dp)
private val ButtonGroupEndShape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp)

/**
 * Meilenstein H.1–H.8, **portiert aus dem ConneXias-Framework-Quellprojekt** (dort:
 * `SentinelActivity`, eigenständige APK/UID) — jetzt Wardens eigener, lokaler
 * PIN-Presence-Screen im selben Prozess, s. Plan-Abschnitt "Presence: Sentinels PIN-Logik
 * portiert". **Manuelles Wiring statt Hilt** (Zero-Dependency-Ideal), unverändert übernommen.
 *
 * **Gegenüber dem Quellprojekt entfernt** (alle drei setzten eine echte, zweite, unabhängige
 * APK/UID voraus — s. Plan-Context-Abschnitt "Bewusster Sicherheits-Trade-off"):
 * - Caller-Verifikation (`isCallerWarden`/`WARDEN_PACKAGE_NAME`) — diese Activity ist nicht mehr
 *   `exported`, es gibt keinen fremden Aufrufer mehr zu verifizieren.
 * - Der komplette Recovery-Bestätigungs-Flow (`EXTRA_RECOVERY_CONFIRMATION_REQUEST`,
 *   `RecoveryConfirmationContent`, `SentinelRecoveryGate.mint()`) — ein
 *   [WardenPinStateDecision.LoadResult.Corrupted]-Zustand wird jetzt stattdessen über den
 *   ohnehin vorhandenen Offline-Failsafe behandelt (kein zweiter, redundanter Mechanismus).
 * - Der Rollback-Abgleich über den Bus (`SentinelBusRepository`/`SentinelRollbackDecision`,
 *   `reportStateBestEffort`) — es gibt keinen zweiten, gespiegelten Stand mehr, gegen den
 *   abgeglichen werden könnte.
 * - Das Presence-Token (`EXTRA_RESULT_PRESENCE_TOKEN`/`SentinelRecoveryGate.mint()`) im
 *   Presence-Bestätigungs-Ergebnis — der Aufrufer (`SensitiveActionActivity`) läuft im selben
 *   Prozess und kann `RESULT_OK` direkt vertrauen, kein Cross-APK-Token-Nachweis nötig.
 *
 * **H.4/H.5 — voller Zustands-Blob statt reinem PIN-Hash:** [WardenPinStore] ersetzt einen
 * einfachen Envelope vollständig. Drei mögliche Ladezustände
 * ([WardenPinStateDecision.LoadResult]) steuern die UI: `NotYetConfigured` (Ersteinrichtung),
 * `Loaded` (PIN-Eingabe), `Corrupted` (Fail-Safe — **kein** Numpad, keine Möglichkeit, sich
 * "durchzumogeln").
 *
 * **H.7 — Anti-Hammering:** `failedAttempts`/`backoffUntilEpochSeconds` leben **im Blob**
 * (überleben einen Reboot, anders als ein reiner Prozessspeicher-Zähler). Jeder Versuch prüft
 * zuerst [WardenAntiHammeringDecision.isAttemptAllowedNow], bevor überhaupt verifiziert wird.
 *
 * **Jede Zustandsänderung ist eine neue Blob-Version:** PIN setzen, erfolgreiches Entsperren UND
 * ein Fehlversuch (Anti-Hammering-Zustand!) laufen alle über [WardenPinStore.persistNewVersion]
 * — eine zurückgespielte alte Version soll auch eine zurückgesetzte Fehlversuchs-Zählung
 * unmöglich machen, deshalb derselbe Zähler-/Ketten-Mechanismus für *jede* Mutation, nicht nur
 * für PIN-Änderungen.
 *
 * **Notruf-Drill-Trigger (H.8, seit "Sentinel: eigenständige Kiosk-PIN-App" auf
 * [SentinelLockdownEngager] umgestellt):** [EXTRA_ENGAGE_LOCK_TASK_DRILL] schaltet real scharf
 * (`SentinelLockdownEngager.engage(context, emergencyCallDrillPassed = true)`, autorisiert
 * Sentinels Paket per DPM und startet dessen `SentinelActivity` — `startLockTask()` selbst läuft
 * danach in Sentinels eigenem Prozess, nicht hier) — über einen normalen `am start`-Intent-Extra,
 * gebraucht deshalb keine laufende Instrumentierung und tut nichts, sofern niemand diesen Extra
 * explizit setzt. **Doppelt abgesichert:** zusätzlich zum expliziten Extra nur wirksam auf einem
 * Debug-Build (`BuildConfig.DEBUG`) — dieselbe "hart, nicht optional"-Haltung wie
 * `DestructiveCommandGuard`. `emergencyCallDrillPassed = true` ist hier korrekt (nicht umgangen):
 * dieser Aufruf **ist** der reale, manuell durchgeführte Drill, den
 * [SentinelLockTaskGate][de.ble1st.warden.sentinel.domain.SentinelLockTaskGate] (Sentinels
 * eigenes, framework-freies Gate) verlangt — kein automatischer/impliziter Pfad.
 *
 * **Presence-Nachweis (Threat Model T4: "Owner presence (biometric / local PIN path)"):**
 * [EXTRA_PRESENCE_REQUEST] lässt [SensitiveActionActivity] diese Activity per
 * `startActivityForResult` aufrufen und ein `RESULT_OK` zurückerhalten, sobald **hier gerade
 * real** eine korrekte PIN eingegeben wurde — kein "Entsperrt"-Zwischenschritt, die Activity
 * schließt sich sofort wieder. **Bewusst kein Weg, über eine frische Ersteinrichtung "Presence"
 * zu erlangen:** [WardenPinScreen] zeigt im Presence-Request-Modus bei `NotYetConfigured` eine
 * Fehlermeldung statt des Einrichtungsdialogs — ein Nachweis muss ein *bereits bestehendes*
 * Owner-Geheimnis bestätigen, nicht ein neues an Ort und Stelle erzeugen dürfen.
 *
 * **Duress-PIN (GrapheneOS-Vorbild, 2026-08-22):** eine zweite, optionale PIN — nur aus dem
 * bereits entsperrten Zustand heraus einrichtbar (s. "PIN ändern" oben, dieselbe Voraussetzung),
 * über [WardenDuressPinStorage] komplett unabhängig vom Haupt-Blob gespeichert. Wird sie statt der
 * Haupt-PIN eingegeben (sowohl beim normalen Entsperren als auch bei einem Presence-Request), löst
 * [DuressPinResponder] einen sofortigen `DevicePolicyManager.reboot()` aus, während die UI exakt
 * wie bei einer falschen Haupt-PIN reagiert — s. [DuressPinResponder]-Klassendoc für die
 * Begründung, warum das ein Reboot statt eines echten Wipes ist.
 *
 * **WardenLock-Sitzungsprüfung, bedingt (Review-Nachtrag 2026-08-24):** [finishIfWardenLockSessionMissing]
 * greift in [onResume] nur im **normalen** Modus (`EXTRA_PRESENCE_REQUEST` nicht gesetzt, also der
 * Einstieg über "Warden-PIN verwalten"), nicht im Presence-Request-Modus — dort *ist* dieser Aufruf
 * gerade der Weg, überhaupt erst eine Sitzung zu erlangen (aus [WardenLockActivity]s Bootstrap-
 * PIN-Weg oder [SensitiveActionActivity]s `executeWithPinPresence`); ein unbedingter Check dort
 * würde sich selbst aushebeln, bevor eine PIN je eingegeben werden könnte. Grund für den Check im
 * Normal-Modus: der Selbstbedienungs-"PIN ändern"/"Duress-PIN einrichten"-Fluss oben verlangt die
 * alte PIN nur einmal (`unlocked = true` gilt danach als Besitznachweis für beliebig viele weitere
 * Schritte, s. `isChangingPin`/`isSettingDuressPin`-Kommentare in [WardenPinScreen]) — dieser
 * Compose-State übersteht ein Backgrounding unverändert (nur `onStop`, keine Zerstörung). Ohne
 * diesen Check würde ein Wiedereinstieg über die Aufgabenübersicht mitten in diesem Fluss (nach
 * korrekter alter PIN, vor Abschluss der Änderung) eine neue Haupt-/Duress-PIN erlauben, ohne dass
 * die ursprüngliche PIN je bekannt sein müsste — genau die Bedrohung, gegen die WardenLock gebaut
 * wurde, s. [WardenLockSession]-Klassendoc.
 */
class WardenPinActivity : ComponentActivity() {

    private val wardenLockSession by lazy { (application as WardenApplication).wardenLockSession }

    private var lockTaskDrillTriggered = false

    /** Bumpt bei jedem `onNewIntent()` — als `key()` um [WardenPinScreen] gelegt, damit ein
     * Re-Launch (Notruf-Drill-Trigger **oder** ein neuer Presence-Request, während die Activity
     * noch von einem vorherigen Aufruf offen ist) den gesamten Compose-`remember`-Zustand
     * verlässlich zurücksetzt, statt eine u. U. veraltete Anzeige stehen zu lassen. */
    private var intentGeneration by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // H.2: FLAG_SECURE vor jedem setContent — verhindert Screenshots/Screen-Recording/
        // Wiedergabe in "Letzte Apps" während der PIN-Eingabe, unabhängig vom Compose-Inhalt.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val blobStore = WardenPinStorage.openStore(applicationContext)
        val duressBlobStore = WardenDuressPinStorage.openStore(applicationContext)
        val duressResponder = DuressPinResponder(applicationContext)
        val logStore = wardenAuditLog(applicationContext)
        // Nur gelesen, nicht hier umschaltbar — s. FailsafeActivity-Kommentar.
        val accent = WardenThemePrefs.load(applicationContext)

        setContent {
            WardenTheme(accent = accent) {
                key(intentGeneration) {
                    val presenceRequested = intent?.getBooleanExtra(EXTRA_PRESENCE_REQUEST, false) == true
                    WardenPinScreen(
                        blobStore = blobStore,
                        duressBlobStore = duressBlobStore,
                        duressResponder = duressResponder,
                        logStore = logStore,
                        isPresenceRequest = presenceRequested,
                        onPresenceConfirmed = {
                            setResult(RESULT_OK)
                            finish()
                        },
                        onOpenFailsafe = {
                            startActivity(Intent(this@WardenPinActivity, FailsafeActivity::class.java))
                            finish()
                        },
                    )
                }
            }
        }
    }

    /** Ohne dieses Override liefert `getIntent()`/`intent` bei einem Re-Launch weiterhin den
     * *ursprünglichen* Intent, mit dem die Activity zuerst erstellt wurde — sowohl
     * [EXTRA_ENGAGE_LOCK_TASK_DRILL] als auch [EXTRA_PRESENCE_REQUEST] würden dann bei einem
     * erneuten `startActivityForResult` auf eine bereits laufende Instanz ignoriert.
     * `setIntent()` + [intentGeneration]-Bump beheben das für beide Fälle einheitlich. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lockTaskDrillTriggered = false
        intentGeneration++
    }

    /** Der `lockTaskDrillTriggered`-Guard verhindert einen erneuten
     * [SentinelLockdownEngager.engage]-Aufruf bei einem späteren `onResume()` (z. B. nach einem
     * Dialog) — `engage()` selbst ist zwar idempotent bezüglich der DPM-Autorisierung, ein
     * wiederholter Log-Eintrag/`startActivity()` auf Sentinel pro `onResume()` wäre trotzdem
     * irreführend für die Drill-Auswertung. */
    override fun onResume() {
        super.onResume()
        // s. Klassendoc "WardenLock-Sitzungsprüfung, bedingt": nur im Normal-Modus, nie im
        // Presence-Request-Modus (sonst würde der Check den Weg zerstören, der die Sitzung
        // überhaupt erst herstellen soll).
        val presenceRequested = intent?.getBooleanExtra(EXTRA_PRESENCE_REQUEST, false) == true
        if (!presenceRequested && finishIfWardenLockSessionMissing(wardenLockSession)) return

        if (lockTaskDrillTriggered) return
        val requested = intent?.getBooleanExtra(EXTRA_ENGAGE_LOCK_TASK_DRILL, false) == true
        if (!requested || !BuildConfig.DEBUG) return
        lockTaskDrillTriggered = true

        val logStore = wardenAuditLog(applicationContext)
        val started = SentinelLockdownEngager.engage(context = applicationContext, emergencyCallDrillPassed = true)
        logStore.append(
            Log.WARN,
            TAG,
            "Notruf-Drill: Sentinel-Scharfschaltung ${if (started) "ausgelöst" else "abgelehnt (Sentinel nicht installiert?)"}",
        )
    }

    companion object {
        /** `adb shell am start -n de.ble1st.warden/.presence.WardenPinActivity ` +
         * `--ez engageLockTaskDrill true`. Öffentlich, damit ein künftiges Test-/Runbook-Tooling
         * nicht den String-Literal-Namen duplizieren muss. */
        const val EXTRA_ENGAGE_LOCK_TASK_DRILL = "engageLockTaskDrill"

        /** Von [SensitiveActionActivity] per `startActivityForResult` gesetzt — s. Klassendoc. */
        const val EXTRA_PRESENCE_REQUEST = "presenceRequest"

        const val TAG = "WardenPin"
    }
}

/**
 * Review-Fund 2026-08-24: `Engine.verifyPassword` wirft `PasswordException.InvalidStoredHash`,
 * wenn `hash` kein gültiges PHC-Format hat (s. `rust/engine/src/password.rs`) — praktisch nie
 * erreichbar, weil der PIN-Hash Teil des Envelope-Klartexts ist und damit schon vom AES-GCM-
 * Auth-Tag geschützt wird, bevor er hier ankommt, aber kein struktureller Ausschluss (z. B. ein
 * Codec-Bug könnte theoretisch ein falsch dekodiertes Feld liefern, das den Auth-Tag trotzdem
 * passiert). Ungefangen hätte das den Compose-Klick-Handler crashen lassen statt der
 * vorgesehenen "Falsche PIN"-Meldung — hier stattdessen einheitlich als "nicht verifiziert"
 * behandelt (Fail-Safe, Invariante 6, dieselbe Semantik wie `password.rs`s eigener
 * `PasswordError::InvalidStoredHash`-Kommentar: "als 'nicht verifizierbar', nie als 'richtig'").
 */
private fun verifyPinHash(pin: ByteArray, hash: String): Boolean =
    runCatching { Engine.verifyPassword(pin, PasswordHash(hash)) }.getOrDefault(false)

@Composable
private fun WardenPinScreen(
    blobStore: WardenPinStore,
    duressBlobStore: WardenPinStore,
    duressResponder: DuressPinResponder,
    logStore: HashChainLogStore,
    isPresenceRequest: Boolean = false,
    onPresenceConfirmed: (() -> Unit)? = null,
    onOpenFailsafe: (() -> Unit)? = null,
) {
    fun currentLoadResult() = WardenPinStateDecision.load(blobStore.exists()) { blobStore.load() }
    fun currentDuressLoad() = WardenPinStateDecision.load(duressBlobStore.exists()) { duressBlobStore.load() }
    fun currentDuressConfigured(): Boolean = when (currentDuressLoad()) {
        is WardenPinStateDecision.LoadResult.Loaded -> true
        is WardenPinStateDecision.LoadResult.Corrupted -> true
        WardenPinStateDecision.LoadResult.NotYetConfigured -> false
    }

    var loadResult by remember { mutableStateOf(currentLoadResult()) }
    var pendingFirstPin by remember { mutableStateOf<List<Int>?>(null) }
    var digits by remember { mutableStateOf(emptyList<Int>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var unlocked by remember { mutableStateOf(false) }
    // Selbstbedienungs-PIN-Änderung: nur aus dem bereits entsperrten Zustand erreichbar — das
    // Entsperren selbst *ist* der Besitznachweis, keine erneute Verifikation der alten PIN
    // nötig, bevor eine neue eingegeben wird. Wiederverwendet onConfirmSetup() unverändert
    // (dasselbe "erst eingeben, dann zur Bestätigung erneut eingeben"-Muster wie bei der
    // Ersteinrichtung), nur mit anderem Log-Text/anderer Meldung.
    var isChangingPin by remember { mutableStateOf(false) }
    // Duress-PIN-Einrichtung (2026-08-22) — dieselbe "nur entsperrt erreichbar"-Voraussetzung wie
    // isChangingPin, aus demselben Grund: das Entsperren mit der Haupt-PIN ist bereits der
    // Besitznachweis.
    var isSettingDuressPin by remember { mutableStateOf(false) }
    var duressConfigured by remember { mutableStateOf(currentDuressConfigured()) }

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
                blobStore.persistNewVersion { current ->
                    current.copy(pinHash = hash.phc, locked = false, failedAttempts = 0, backoffUntilEpochSeconds = 0)
                }
                logStore.append(Log.INFO, "WardenPin", if (isChangingPin) "pin changed" else "pin configured")
                pendingFirstPin = null
                message = if (isChangingPin) "PIN geändert." else "PIN gespeichert."
                isChangingPin = false
                loadResult = currentLoadResult()
            }
            else -> {
                pendingFirstPin = null
                message = "PINs stimmten nicht überein — von vorn."
            }
        }
        digits = emptyList()
    }

    /** Dasselbe "erst eingeben, dann zur Bestätigung erneut eingeben"-Muster wie [onConfirmSetup],
     * schreibt aber in [duressBlobStore] statt [blobStore]. `mainBlob` wird gebraucht, um
     * sicherzustellen, dass die neue Duress-PIN sich von der aktuellen Haupt-PIN unterscheidet —
     * wären beide identisch, könnte [onConfirmVerify] nie zwischen normalem Entsperren und Duress
     * unterscheiden (die Duress-Prüfung dort läuft zuerst, s. dortiger Kommentar). */
    fun onConfirmDuressSetup(mainBlob: WardenPinBlob) {
        if (digits.size < MIN_PIN_LENGTH) return
        val firstPin = pendingFirstPin
        when {
            firstPin == null -> {
                pendingFirstPin = digits
                message = "Jetzt zur Bestätigung erneut eingeben."
            }
            firstPin == digits -> {
                val pinBytes = digits.joinToString(separator = "").toByteArray(StandardCharsets.UTF_8)
                val matchesMainPin = verifyPinHash(pinBytes, mainBlob.pinHash)
                if (matchesMainPin) {
                    pinBytes.fill(0)
                    pendingFirstPin = null
                    message = "Duress-PIN darf nicht der Haupt-PIN entsprechen — von vorn."
                } else {
                    val hash = Engine.hashPassword(pinBytes)
                    pinBytes.fill(0)
                    duressBlobStore.persistNewVersion { current ->
                        current.copy(pinHash = hash.phc, locked = false, failedAttempts = 0, backoffUntilEpochSeconds = 0)
                    }
                    logStore.append(Log.INFO, "WardenPin", "duress pin configured")
                    pendingFirstPin = null
                    message = "Duress-PIN gespeichert."
                    isSettingDuressPin = false
                    duressConfigured = currentDuressConfigured()
                }
            }
            else -> {
                pendingFirstPin = null
                message = "PINs stimmten nicht überein — von vorn."
            }
        }
        digits = emptyList()
    }

    fun onClearDuressPin() {
        duressBlobStore.persistNewVersion { current ->
            current.copy(pinHash = "", locked = false, failedAttempts = 0, backoffUntilEpochSeconds = 0)
        }
        logStore.append(Log.INFO, "WardenPin", "duress pin cleared")
        duressConfigured = currentDuressConfigured()
        message = "Duress-PIN gelöscht."
    }

    fun onConfirmVerify(blob: WardenPinBlob) {
        if (digits.size < UNLOCK_MIN_PIN_LENGTH) return
        val nowSeconds = System.currentTimeMillis() / 1000
        if (!WardenAntiHammeringDecision.isAttemptAllowedNow(blob.backoffUntilEpochSeconds, nowSeconds)) {
            message = "Gesperrt — noch ${blob.backoffUntilEpochSeconds - nowSeconds}s warten."
            digits = emptyList()
            return
        }

        val pinBytes = digits.joinToString(separator = "").toByteArray(StandardCharsets.UTF_8)

        // Duress-Prüfung **zuerst**, vor der normalen Haupt-PIN-Verifikation — s.
        // DuressPinResponder-Klassendoc: auf dem Bildschirm identisch zu einer falschen Haupt-PIN
        // behandelt (derselbe Anti-Hammering-Zähler auf blobStore, dieselbe Meldung), kein
        // sichtbarer Hinweis außer dem tatsächlichen Reboot. Gilt unverändert auch im
        // Presence-Request-Modus: onPresenceConfirmed wird dabei nie aufgerufen, die angeforderte
        // Aktion bleibt also ebenfalls verweigert.
        val duressHash = (currentDuressLoad() as? WardenPinStateDecision.LoadResult.Loaded)?.blob?.pinHash.orEmpty()
        val isDuress = duressHash.isNotEmpty() && verifyPinHash(pinBytes, duressHash)
        if (isDuress) {
            pinBytes.fill(0)
            logStore.append(Log.ERROR, "WardenPin", "duress pin entered — triggering protective reboot")
            val persisted = blobStore.persistNewVersion { current ->
                val failedAttempts = current.failedAttempts + 1
                val backoff = WardenAntiHammeringDecision.backoffSecondsFor(failedAttempts)
                current.copy(locked = true, failedAttempts = failedAttempts, backoffUntilEpochSeconds = nowSeconds + backoff)
            }
            val backoff = persisted.backoffUntilEpochSeconds - nowSeconds
            message = if (backoff > 0) "Falsche PIN — Sperre für ${backoff}s." else "Falsche PIN."
            loadResult = currentLoadResult()
            digits = emptyList()
            runCatching { duressResponder.trigger() }
                .onFailure { logStore.append(Log.ERROR, "WardenPin", "duress reboot failed: $it") }
            return
        }

        val result = WardenPinDecision.evaluate(
            storedHash = blob.pinHash.ifEmpty { null },
            enteredPin = pinBytes,
            verify = { pin, hash -> verifyPinHash(pin, hash) },
        )
        pinBytes.fill(0)

        when (result) {
            WardenPinDecisionResult.Accepted -> {
                blobStore.persistNewVersion { current ->
                    current.copy(locked = false, failedAttempts = 0, backoffUntilEpochSeconds = 0)
                }
                if (isPresenceRequest) {
                    // Presence-Nachweis (s. Klassendoc): der Aufrufer bekommt sein RESULT_OK
                    // jetzt — kein "Entsperrt"-Zwischenschritt, die Activity schließt sich
                    // sofort wieder, das war der einzige Zweck dieses Aufrufs.
                    logStore.append(Log.WARN, "WardenPin", "presence proof: pin verified")
                    onPresenceConfirmed?.invoke()
                } else {
                    logStore.append(Log.INFO, "WardenPin", "pin verified, unlocked")
                    unlocked = true
                    message = "Entsperrt."
                }
            }
            WardenPinDecisionResult.Rejected -> {
                val persisted = blobStore.persistNewVersion { current ->
                    val failedAttempts = current.failedAttempts + 1
                    val backoff = WardenAntiHammeringDecision.backoffSecondsFor(failedAttempts)
                    current.copy(locked = true, failedAttempts = failedAttempts, backoffUntilEpochSeconds = nowSeconds + backoff)
                }
                val backoff = persisted.backoffUntilEpochSeconds - nowSeconds
                logStore.append(Log.WARN, "WardenPin", "pin rejected, failedAttempts=${persisted.failedAttempts} backoffSeconds=$backoff")
                message = if (backoff > 0) "Falsche PIN — Sperre für ${backoff}s." else "Falsche PIN."
            }
            WardenPinDecisionResult.NotConfigured -> {
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
            Text(text = "Warden-PIN", style = MaterialTheme.typography.headlineMedium)
            if (isPresenceRequest) {
                Text(
                    text = "Präsenznachweis angefordert",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            when (val result = loadResult) {
                WardenPinStateDecision.LoadResult.NotYetConfigured -> if (isPresenceRequest) {
                    // Presence muss ein *bestehendes* Owner-Geheimnis bestätigen — eine
                    // Ersteinrichtung an Ort und Stelle wäre kein Nachweis, s. Klassendoc.
                    Text(
                        text = "⚠ Noch keine PIN eingerichtet — Presence-Nachweis nicht möglich. " +
                            "Zuerst eine PIN festlegen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                } else {
                    SetupContent(
                        subtitle = if (pendingFirstPin == null) "PIN festlegen (6–8 Ziffern)" else "PIN zur Bestätigung erneut eingeben",
                        message = message,
                        digits = digits,
                        onDigit = ::onDigit,
                        onBackspace = ::onBackspace,
                        onConfirm = ::onConfirmSetup,
                    )
                }

                is WardenPinStateDecision.LoadResult.Loaded -> if (unlocked) {
                    if (isChangingPin) {
                        SetupContent(
                            subtitle = if (pendingFirstPin == null) "Neue PIN festlegen (6–8 Ziffern)" else "Neue PIN zur Bestätigung erneut eingeben",
                            message = message,
                            digits = digits,
                            onDigit = ::onDigit,
                            onBackspace = ::onBackspace,
                            onConfirm = ::onConfirmSetup,
                        )
                        Button(
                            onClick = { isChangingPin = false; pendingFirstPin = null; digits = emptyList(); message = null },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("Abbrechen")
                        }
                    } else if (isSettingDuressPin) {
                        SetupContent(
                            subtitle = if (pendingFirstPin == null) "Duress-PIN festlegen (6–8 Ziffern)" else "Duress-PIN zur Bestätigung erneut eingeben",
                            message = message,
                            digits = digits,
                            onDigit = ::onDigit,
                            onBackspace = ::onBackspace,
                            onConfirm = { onConfirmDuressSetup(result.blob) },
                        )
                        Button(
                            onClick = { isSettingDuressPin = false; pendingFirstPin = null; digits = emptyList(); message = null },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("Abbrechen")
                        }
                    } else {
                        Text(text = "Entsperrt", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                        Button(
                            onClick = { unlocked = false; digits = emptyList(); message = null },
                            modifier = Modifier.padding(top = 24.dp),
                        ) {
                            Text("Erneut sperren")
                        }
                        // Punkt 7 ("weitere App-UI-Verschönerungen", 2026-08-22) — die beiden
                        // gleichrangigen "PIN-Verwaltung ändern"-Einstiege zu einer verbundenen
                        // Button-Gruppe zusammengefasst statt einzeln gestapelt. Material 3
                        // Expressives eigene, neue "Button Groups"-Komponente steckt noch in
                        // material3 1.5.0-alpha (nicht in der hier gepinnten stabilen 1.4.0) —
                        // dieselbe verbundene Optik hier stattdessen mit zwei normalen `Button`s
                        // und außen abgerundeten/innen eckigen Formen nachgebaut. "Duress-PIN
                        // löschen" bleibt bewusst außerhalb der Gruppe, eigene Zeile: eine
                        // löschende Aktion soll nicht dieselbe beiläufige Gewichtung bekommen wie
                        // die beiden Einrichtungs-Einstiege.
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Button(
                                onClick = { isChangingPin = true; pendingFirstPin = null; digits = emptyList(); message = null },
                                shape = ButtonGroupStartShape,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("PIN ändern", maxLines = 1)
                            }
                            Button(
                                onClick = { isSettingDuressPin = true; pendingFirstPin = null; digits = emptyList(); message = null },
                                shape = ButtonGroupEndShape,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (duressConfigured) "Duress-PIN ändern" else "Duress-PIN einrichten", maxLines = 1)
                            }
                        }
                        if (duressConfigured) {
                            Button(
                                onClick = ::onClearDuressPin,
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Text("Duress-PIN löschen")
                            }
                        }
                    }
                } else {
                    val nowSeconds = System.currentTimeMillis() / 1000
                    val blocked = !WardenAntiHammeringDecision.isAttemptAllowedNow(result.blob.backoffUntilEpochSeconds, nowSeconds)
                    SetupContent(
                        subtitle = "PIN eingeben",
                        message = message,
                        digits = digits,
                        onDigit = ::onDigit,
                        onBackspace = ::onBackspace,
                        onConfirm = { onConfirmVerify(result.blob) },
                        confirmExtraDisabled = blocked,
                        minConfirmLength = UNLOCK_MIN_PIN_LENGTH,
                    )
                }

                is WardenPinStateDecision.LoadResult.Corrupted -> {
                    // Anders als im Quellprojekt (dort: Reset nur über Wardens vertrauenswürdigen
                    // Cross-APK-Pfad) gibt es hier keinen zweiten Mechanismus mehr — dieser
                    // Zustand wird über den ohnehin vorhandenen Offline-Failsafe behandelt, s.
                    // Klassendoc.
                    Text(
                        text = "⚠ Zustand beschädigt — Wiederherstellung über den Offline-Failsafe",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    onOpenFailsafe?.let { openFailsafe ->
                        Button(onClick = openFailsafe, modifier = Modifier.padding(top = 16.dp)) {
                            Text("Zum Offline-Failsafe")
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
    minConfirmLength: Int = MIN_PIN_LENGTH,
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
        confirmEnabled = digits.size >= minConfirmLength && !confirmExtraDisabled,
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
    // Punkt 4 ("weitere App-UI-Verschönerungen", 2026-08-22) — kurzes haptisches Feedback statt
    // reiner Optik: bei einer PIN-Eingabe ohne visuelles Ziffern-Feedback (nur Punkte, keine
    // Zahlen) bestätigt ein Tastendruck spürbar statt nur sichtbar. `LongPress` ist hier (wie in
    // vielen Compose-Apps) die gängige Wahl für eine generische Tastendruck-Rückmeldung — nur
    // `LongPress`/`TextHandleMove` sind in der aktuell aufgelösten Compose-UI-Version verfügbar,
    // die neueren, benannten Typen (`Confirm`/`Reject`/…) noch nicht.
    val haptic = LocalHapticFeedback.current
    Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = enabled,
        modifier = Modifier
            .size(72.dp)
            .semantics { contentDescription = description },
    ) {
        Text(text = label, style = MaterialTheme.typography.titleLarge)
    }
}
