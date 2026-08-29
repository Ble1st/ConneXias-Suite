package de.ble1st.warden.failsafe

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import de.ble1st.warden.crypto.OfflineFailsafeVerifier
import de.ble1st.warden.domain.registry.SafeguardRegistry
import de.ble1st.warden.logging.HashChainLogStore
import de.ble1st.warden.presence.PresenceManager
import de.ble1st.warden.wardenAuditLog
import de.ble1st.warden.presence.WardenPinActivity
import de.ble1st.warden.pin.WardenDuressPinStorage
import de.ble1st.warden.pin.WardenPinStorage
import de.ble1st.warden.registry.MasterSwitch
import de.ble1st.warden.registry.PersistentSafeguardRegistry
import de.ble1st.warden.registry.RegistryStorage
import de.ble1st.warden.registry.SafeguardCatalog
import de.ble1st.warden.registry.SafeguardRegistryStore
import de.ble1st.warden.ui.theme.WardenTheme
import de.ble1st.warden.ui.theme.WardenThemePrefs

/**
 * Meilenstein D.3 (Konzept Abschnitt 9): minimale, aber **reale** Bedienoberfläche für den
 * Offline-Failsafe — "Challenge auf dem Gerät erzeugt/angezeigt, Response offline berechnet,
 * lokal eingegeben". Anders als das C.5/C.6-Lockdown-Bündel/den Masterschalter (bewusst ohne
 * erreichbaren Trigger, weil Presence-Infrastruktur fehlt) ist der Offline-Failsafe **selbst**
 * der presence-äquivalente Nachweis (Besitz des Offline-Schlüssels) — ohne einen erreichbaren
 * Trigger wäre dieser Teil nicht "sicher genug zum Nutzen".
 *
 * Erreichbar über einen Button auf `WardenStatusActivity`. Dient außerdem als
 * Wiederherstellungsweg für einen [de.ble1st.warden.domain.pin.WardenPinStateDecision.LoadResult
 * .Corrupted]-Zustand des lokalen PIN-Blobs (s. [WardenPinActivity]-Klassendoc) — es gibt in
 * diesem Projekt keinen zweiten, cross-APK-vermittelten Recovery-Mechanismus mehr dafür.
 *
 * **Bewusst schlicht** (kein ViewModel/State-Restoration über Prozesstod hinweg — die
 * persistierte Challenge in [FailsafeChallengeStore] überlebt das, die reine UI-Anzeige nicht;
 * ein Prozesstod mitten im Ausfüllen der Response bedeutet nur, dass die Challenge erneut über
 * "Challenge erzeugen" abgerufen werden muss, kein Datenverlust). Kein zusätzliches Presence-Gate
 * für den eigentlichen Failsafe-Vorgang selbst (Challenge/Response) — eine gültige, offline
 * berechnete Ed25519-Response *ist* bereits der presence-äquivalente Nachweis (Besitz des
 * Offline-Schlüssels).
 *
 * **Das Hinterlegen/Ersetzen des Wartungsschlüssels selbst braucht einen eigenen Nachweis** —
 * wer die Status-UI bedienen kann (physischer Zugriff auf ein entsperrtes Gerät), könnte sonst
 * den Verify-Key ersetzen und danach den gesamten Offline-Failsafe kontrollieren, ohne dass
 * Envelope-Verschlüsselung dagegen hilft (schützt nur Integrität am Ruheort, nicht den
 * Owner-/Gleich-UID-Schreibpfad). Deshalb hinter demselben zweigleisigen Presence-Mechanismus
 * wie [de.ble1st.warden.presence.LogViewerActivity]/`SensitiveActionActivity` (Biometrie ODER
 * Wardens lokaler PIN, Threat Model T4) — deshalb [FragmentActivity] statt `ComponentActivity`
 * (Plattform-Anforderung von `BiometricPrompt`, s. `PresenceManager`-Klassendoc). Zusätzlich:
 * `FLAG_SECURE` (Wartungsschlüssel/Challenge/Response sind sicherheitsrelevant, gehören nicht in
 * Screenshots/Recents) und die neue Geräte-PIN wird per [PasswordVisualTransformation] maskiert
 * statt im Klartext angezeigt.
 *
 * Anders als im ConneXias-Framework-Quellprojekt (dort: Cross-APK-Presence-Aufruf gegen
 * Sentinels eigene APK, samt Zertifikats-Pinning) startet der PIN-Presence-Pfad hier
 * [WardenPinActivity] direkt in derselben APK — s. `SensitiveActionActivity`-Klassendoc für die
 * ausführliche Begründung.
 */
class FailsafeActivity : FragmentActivity() {

    private var pendingPinPresenceResult: ((Boolean) -> Unit)? = null

    private val pinPresenceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        val callback = pendingPinPresenceResult
        pendingPinPresenceResult = null
        callback?.invoke(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Wartungsschlüssel/Challenge/Response nie in Screenshots/Recents/Screen-Recording.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val keyStore = FailsafeKeyStore(FailsafeStorage.buildTrustedKeyFile(applicationContext))
        val executor = buildExecutor(applicationContext, keyStore)
        val presenceManager = PresenceManager(this)
        // Nur gelesen, nicht hier umschaltbar — der Akzent-Picker lebt auf dem Dashboard
        // (WardenStatusActivity), diese Activity übernimmt lediglich die zuletzt gewählte Farbe.
        val accent = WardenThemePrefs.load(applicationContext)

        setContent {
            WardenTheme(accent = accent) {
                FailsafeScreen(
                    executor = executor,
                    keyStore = keyStore,
                    onRequestPresence = { onGranted ->
                        presenceManager.request(
                            title = "Wartungsschlüssel-Wechsel bestätigen",
                            subtitle = "Ersetzt den Offline-Failsafe-Verify-Key",
                        ) { result ->
                            onGranted(
                                when (result) {
                                    is PresenceManager.Result.Success -> result.proof.consume()
                                    PresenceManager.Result.Unavailable, PresenceManager.Result.Cancelled -> false
                                },
                            )
                        }
                    },
                    onRequestPinPresence = { onGranted ->
                        pendingPinPresenceResult = onGranted
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
        private const val TAG = "FailsafeActivity"

        /** Full catalog including lockdown — failsafe is the recovery path and must actually
         * disarm every registered restriction, not a three-entry subset. */
        private fun buildExecutor(context: Context, keyStore: FailsafeKeyStore): OfflineFailsafeExecutor {
            val registry = PersistentSafeguardRegistry(
                SafeguardRegistry(),
                SafeguardRegistryStore(RegistryStorage.buildEnvelopeFile(context)),
            )
            SafeguardCatalog.registerAll(registry, context)
            registry.load()
            val masterSwitch = MasterSwitch(registry)

            val credentialResetter = DeviceCredentialResetter(
                context,
                FailsafeStorage.buildResetTokenFile(context),
            )
            val logStore = wardenAuditLog(context)
            val pinStore = WardenPinStorage.openStore(context)
            val duressStore = WardenDuressPinStorage.openStore(context)

            return OfflineFailsafeExecutor(
                challengeStore = FailsafeChallengeStore(FailsafeStorage.buildChallengeFile(context)),
                keyStore = keyStore,
                verifier = OfflineFailsafeVerifier(),
                revertAllSafeguards = { masterSwitch.disarm() },
                resetDeviceCredential = { newPassword ->
                    credentialResetter.ensureActive()
                    credentialResetter.reset(newPassword)
                },
                resetLocalPinSecrets = {
                    pinStore.clearForRecovery()
                    duressStore.clearForRecovery()
                },
                onEvent = { event -> logEvent(logStore, event) },
            )
        }

        private fun logEvent(logStore: HashChainLogStore, event: OfflineFailsafeEvent) {
            when (event) {
                is OfflineFailsafeEvent.ChallengeIssued -> logStore.append(
                    priority = Log.INFO,
                    tag = TAG,
                    message = "failsafe challenge issued",
                )
                is OfflineFailsafeEvent.ResponseSubmitted -> logStore.append(
                    priority = if (event.result is OfflineFailsafeResult.Accepted) Log.WARN else Log.INFO,
                    tag = TAG,
                    message = "failsafe response submitted -> ${event.result::class.simpleName}",
                )
            }
        }
    }
}

@Composable
private fun FailsafeScreen(
    executor: OfflineFailsafeExecutor,
    keyStore: FailsafeKeyStore,
    onRequestPresence: ((Boolean) -> Unit) -> Unit,
    onRequestPinPresence: ((Boolean) -> Unit) -> Unit,
) {
    var publicKeyHex by remember { mutableStateOf("") }
    var challengeHex by remember { mutableStateOf<String?>(null) }
    var responseHex by remember { mutableStateOf("") }
    var newCredential by remember { mutableStateOf("") }
    var statusMessage by remember {
        mutableStateOf(
            runCatching { statusFor(keyStore.configuredPublicKey()) }
                .getOrElse { "⚠ Wartungsschlüssel unlesbar — nicht als unkonfiguriert behandeln." },
        )
    }

    fun applyPublicKeyIfPresenceGranted(granted: Boolean) {
        statusMessage = if (!granted) {
            "⚠ Presence-Nachweis fehlgeschlagen/abgebrochen — Wartungsschlüssel NICHT geändert."
        } else {
            val bytes = decodeHexOrNull(publicKeyHex)
            if (bytes == null) {
                "Ungültiges Hex-Format."
            } else {
                runCatching { keyStore.configurePublicKey(bytes) }
                    .fold(
                        onSuccess = { statusFor(keyStore.configuredPublicKey()) },
                        onFailure = { e -> "Fehler: ${e.message}" },
                    )
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Offline-Failsafe", style = MaterialTheme.typography.headlineSmall)
            Text(text = statusMessage, style = MaterialTheme.typography.bodyMedium)

            Text(
                text = "1. Wartungsschlüssel hinterlegen (einmalig, offline erzeugt)",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Das Ersetzen des Verify-Keys braucht einen Presence-Nachweis — Biometrie " +
                    "oder Warden-PIN.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = publicKeyHex,
                onValueChange = { publicKeyHex = it },
                label = { Text("Public Key (hex, 64 Zeichen)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onRequestPresence(::applyPublicKeyIfPresenceGranted) }) {
                    Text("Mit Biometrie speichern")
                }
                TextButton(onClick = { onRequestPinPresence(::applyPublicKeyIfPresenceGranted) }) {
                    Text("Mit Warden-PIN speichern")
                }
            }

            Text(text = "2. Challenge erzeugen", style = MaterialTheme.typography.titleSmall)
            Button(onClick = { challengeHex = encodeHex(executor.issueChallenge()) }) {
                Text("Challenge erzeugen")
            }
            challengeHex?.let { hex ->
                Text(
                    text = "Challenge (auf Air-Gap-Maschine mit failsafe-keytool signieren):\n$hex",
                    style = MaterialTheme.typography.bodySmall,
                )
                // Seit 2026-08-28 gehört die neue Geräte-PIN mit in die signierte Nachricht
                // (FailsafeResponseMessage) — ohne diesen Hinweis würde die Betreiberin nach
                // altem Muster signieren und bekäme auf dem Gerät nur ein unerklärliches
                // "Response ungültig".
                Text(
                    text = "Die neue Geräte-PIN vorher festlegen und beim Signieren als drittes " +
                        "Argument mitgeben:\nfailsafe-keytool sign <secret_key> <challenge> " +
                        "<neue Geräte-PIN>\nUnten muss exakt dieselbe PIN stehen — sie ist Teil " +
                        "der Signatur.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(text = "3. Response eingeben und ausführen", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = responseHex,
                onValueChange = { responseHex = it },
                label = { Text("Response (hex, 128 Zeichen)") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newCredential,
                onValueChange = { newCredential = it },
                label = { Text("Neue Geräte-PIN (16 Zeichen, keine Folge wie 1234…)") },
                // Nicht mehr im Klartext (die Screen ist zusätzlich per FLAG_SECURE ohnehin nicht
                // screenshottbar, Maskierung schützt zusätzlich gegen einfaches
                // Über-die-Schulter-Mitlesen).
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                val response = decodeHexOrNull(responseHex)
                statusMessage = if (response == null) {
                    "Ungültiges Hex-Format für Response."
                } else {
                    runCatching { describeResult(executor.submitResponse(response, newCredential)) }
                        .getOrElse { "Fehler: ${it.message}" }
                }
            }) {
                Text("Failsafe ausführen")
            }
        }
    }
}

private fun statusFor(publicKey: ByteArray?): String =
    if (publicKey == null) {
        "⚠ Kein Wartungsschlüssel hinterlegt — Failsafe nicht auslösbar."
    } else {
        "Wartungsschlüssel konfiguriert (${publicKey.size} Byte)."
    }

private fun describeResult(result: OfflineFailsafeResult): String = when (result) {
    is OfflineFailsafeResult.NoKeyConfigured -> "⚠ Kein Wartungsschlüssel hinterlegt."
    is OfflineFailsafeResult.NoChallengePending -> "Keine Challenge ausstehend — zuerst erzeugen."
    is OfflineFailsafeResult.ChallengeExpired -> "Challenge abgelaufen — bitte eine neue erzeugen."
    is OfflineFailsafeResult.WeakCredential ->
        "Neue Geräte-PIN erfüllt die Vorgabe nicht (16 Zeichen, keine einfache Ziffernfolge). Challenge bleibt bestehen."
    is OfflineFailsafeResult.Rejected ->
        "Response ungültig — nichts wurde verändert. Häufigste Ursache: die hier eingegebene " +
            "Geräte-PIN weicht von der ab, die beim Signieren übergeben wurde (sie ist Teil der " +
            "signierten Nachricht). Die Challenge bleibt bestehen."
    is OfflineFailsafeResult.Accepted -> {
        val revertSummary = result.revertResults.joinToString { "${it.id}=${it::class.simpleName}" }
        if (result.credentialResetSucceeded) {
            "Failsafe ausgeführt. Registry: $revertSummary. Geräte-PIN-Reset: OK"
        } else {
            "Failsafe ausgeführt, aber der Geräte-PIN-Reset ist FEHLGESCHLAGEN. Registry: " +
                "$revertSummary. Die Challenge ist verbraucht — für einen neuen Versuch eine " +
                "neue Challenge erzeugen und erneut signieren."
        }
    }
}

private val HEX_CHARS = "0123456789abcdefABCDEF".toSet()

private fun decodeHexOrNull(hex: String): ByteArray? {
    val trimmed = hex.trim()
    if (trimmed.isEmpty() || trimmed.length % 2 != 0 || trimmed.any { it !in HEX_CHARS }) return null
    return ByteArray(trimmed.length / 2) { i ->
        trimmed.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

private fun encodeHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
