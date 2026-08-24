package de.ble1st.warden.presence

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import de.ble1st.warden.WardenApplication
import de.ble1st.warden.logging.ChainVerificationResult
import de.ble1st.warden.logging.HashChainLogStore
import de.ble1st.warden.logging.LogEntry
import de.ble1st.warden.wardenAuditLog
import de.ble1st.warden.ui.theme.WardenTheme
import de.ble1st.warden.ui.theme.WardenThemePrefs

/**
 * Meilenstein G.4 (Konzept Abschnitt 19: "Log-Einsicht hinter Presence"). Wardens **eigene**
 * Activity, gestartet von `ConcordBus.requestLogAccess` über [Intent.FLAG_ACTIVITY_NEW_TASK]
 * (der Aufruf kommt aus einer normalen In-Process-Methode, nicht mehr aus einem Binder-Thread —
 * `FLAG_ACTIVITY_NEW_TASK` bleibt trotzdem nötig/harmlos, falls `ConcordBus` selbst je aus einem
 * Nicht-Activity-Kontext heraus aufgerufen wird).
 *
 * **Autorisierung:** [de.ble1st.warden.bus.ConcordBus.requestLogAccess] entscheidet bereits, *ob*
 * diese Activity angestoßen werden darf (Capability-Matrix + Rate-Limit). Diese Activity selbst
 * holt danach den eigentlichen Presence-Nachweis (F.1) ein und zeigt *nichts*, bevor der nicht
 * erfolgreich erbracht wurde. Kein Bestätigungstext nötig (anders als
 * [SensitiveActionActivity]) — Log-Einsicht ist rein lesend, keine mehrstufige Bestätigung wie
 * bei destruktiven Kommandos gefordert.
 *
 * **Presence-Reaktivierung über Wardens lokalen PIN (Threat Model T4, dasselbe Muster wie
 * [SensitiveActionActivity]):** zweiter, gleichrangiger Presence-Weg neben Biometrie —
 * `startActivityForResult` gegen [WardenPinActivity] (dieselbe eigene APK, kein Cross-APK-
 * Zertifikats-Pinning mehr nötig, s. [SensitiveActionActivity]-Klassendoc), `RESULT_OK` bei
 * frisch verifizierter PIN.
 *
 * **WardenLock-Sitzungsprüfung (Review-Nachtrag 2026-08-24):** [finishIfWardenLockSessionMissing]
 * in [onResume], unbedingt — anders als bei [WardenPinActivity] gibt es hier keinen
 * Presence-Request-Modus, der sich selbst aushebeln könnte. Grund: der bereits freigeschaltete
 * Log-Inhalt (`outcome = LogAccessOutcome.Granted`, unten) ist reiner Compose-`remember`-State und
 * übersteht ein Backgrounding unverändert (nur `onStop`, keine Zerstörung) — ohne diesen Check
 * bliebe ein schon eingesehenes Log über einen Wiedereinstieg via Aufgabenübersicht sichtbar, ohne
 * erneuten Presence-Nachweis.
 */
class LogViewerActivity : FragmentActivity() {

    private val wardenLockSession by lazy { (application as WardenApplication).wardenLockSession }

    private var pendingPinPresenceResult: ((Boolean) -> Unit)? = null

    private val pinPresenceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        val callback = pendingPinPresenceResult
        pendingPinPresenceResult = null
        callback?.invoke(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val presenceManager = PresenceManager(this)
        val logStore = wardenAuditLog(applicationContext)
        // Nur gelesen, nicht hier umschaltbar — s. FailsafeActivity-Kommentar.
        val accent = WardenThemePrefs.load(applicationContext)

        setContent {
            WardenTheme(accent = accent) {
                LogViewerScreen(
                    onRequestPresence = { onResult ->
                        presenceManager.request(
                            title = "Log-Einsicht bestätigen",
                            subtitle = "Zugriff auf das sicherheitsrelevante Log (Konzept G.4)",
                        ) { result ->
                            when (result) {
                                is PresenceManager.Result.Success -> {
                                    val consumed = result.proof.consume()
                                    onResult(
                                        if (consumed) LogAccessOutcome.Granted.from(logStore) else LogAccessOutcome.Denied,
                                    )
                                }
                                PresenceManager.Result.Unavailable ->
                                    onResult(LogAccessOutcome.Unavailable)
                                PresenceManager.Result.Cancelled ->
                                    onResult(LogAccessOutcome.Denied)
                            }
                        }
                    },
                    onRequestPinPresence = { onResult ->
                        pendingPinPresenceResult = { granted ->
                            onResult(if (granted) LogAccessOutcome.Granted.from(logStore) else LogAccessOutcome.Denied)
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

    override fun onResume() {
        super.onResume()
        finishIfWardenLockSessionMissing(wardenLockSession)
    }
}

private sealed class LogAccessOutcome {
    data class Granted(
        val entries: List<LogEntry>,
        val chain: ChainVerificationResult,
    ) : LogAccessOutcome() {
        companion object {
            fun from(logStore: HashChainLogStore): Granted {
                val entries = logStore.entries()
                return Granted(entries, logStore.verifyChainIntegrity())
            }
        }
    }
    data object Denied : LogAccessOutcome()
    data object Unavailable : LogAccessOutcome()
}

@Composable
private fun LogViewerScreen(
    onRequestPresence: ((LogAccessOutcome) -> Unit) -> Unit,
    onRequestPinPresence: ((LogAccessOutcome) -> Unit) -> Unit,
) {
    var outcome by remember { mutableStateOf<LogAccessOutcome?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Log-Einsicht", style = MaterialTheme.typography.headlineSmall)

            when (val current = outcome) {
                null -> {
                    Text(
                        "Presence-Nachweis erforderlich, bevor der Log-Inhalt angezeigt wird (Konzept G.4).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { onRequestPresence { result -> outcome = result } }) {
                        Text("Mit Biometrie bestätigen")
                    }
                    TextButton(onClick = { onRequestPinPresence { result -> outcome = result } }) {
                        Text("Mit Warden-PIN bestätigen")
                    }
                }
                LogAccessOutcome.Unavailable ->
                    Text(
                        "⚠ Keine Biometrie eingerichtet — Log-Einsicht nicht möglich.",
                        color = MaterialTheme.colorScheme.error,
                    )
                LogAccessOutcome.Denied ->
                    Text("Abgebrochen oder fehlgeschlagen — kein Log-Inhalt angezeigt.")
                is LogAccessOutcome.Granted ->
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item {
                            Text(
                                text = when (val chain = current.chain) {
                                    is ChainVerificationResult.Valid ->
                                        "Kette gültig (${chain.entryCount} Einträge)"
                                    is ChainVerificationResult.Broken ->
                                        "⚠ Kette gebrochen bei #${chain.atSequence}: ${chain.reason}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (current.chain is ChainVerificationResult.Broken) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        items(current.entries.asReversed()) { entry ->
                            Text(
                                text = "#${entry.sequence} [${entry.tag}] ${entry.message}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
            }
        }
    }
}
