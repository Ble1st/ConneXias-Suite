// ⏸ PAUSIERT (2026-08-27): "Netz-Sperre" ist vorübergehend deaktiviert — Live-Test auf dem
// physischen Testgerät fand nach mehreren echten Bugfixes (siehe Commit 7252396 und
// warden-netzsperre-feature-2026-08-27-Memo) einen weiterhin ungeklärten Kernfehler: die
// DNS-Blockliste/NAT-Relay verarbeitet auf einem frisch aufgebauten Tunnel keinen Traffic mehr,
// Ursache unbekannt. Diese Datei liegt deshalb bewusst außerhalb jedes Gradle-Source-Sets
// (app/netlock-disabled/ statt app/src/main/java/) — wird NICHT mitkompiliert, ist nirgendwo
// verkabelt. Zum Reaktivieren: Verzeichnis zurück nach app/src/main/java/... verschieben, alle
// Wiederverkabelungsstellen aus dem Deaktivierungs-Commit rückgängig machen (siehe dessen
// Commit-Message für die vollständige Liste), Kernfehler zuerst klären.

package de.ble1st.warden.netlock

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import de.ble1st.warden.WardenApplication
import de.ble1st.warden.presence.PresenceManager
import de.ble1st.warden.presence.WardenPinActivity
import de.ble1st.warden.presence.finishIfWardenLockSessionMissing
import de.ble1st.warden.ui.theme.WardenTheme
import de.ble1st.warden.ui.theme.WardenThemePrefs
import java.text.DateFormat
import java.util.Date

/**
 * "Netz-Sperre" (2026-08-27): presence-gated Ansicht von [NetworkEventLogStore] — identisches
 * Gating-Muster wie [de.ble1st.warden.presence.LogViewerActivity] (s. dortiges Klassendoc für die
 * volle Begründung: Biometrie- oder PIN-Presence, nichts wird angezeigt, bevor einer der beiden
 * Nachweise erbracht wurde; `finishIfWardenLockSessionMissing` in [onResume] schließt dieselbe
 * "wieder erreicht via Aufgabenübersicht, ohne erneuten Presence-Nachweis"-Lücke). Rein lesend,
 * kein Bestätigungstext nötig — dieselbe Einordnung wie `LogViewerActivity`.
 */
class NetworkLogViewerActivity : FragmentActivity() {

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
        val logStore = NetworkEventLogStore(NetworkEventLogStore.buildEnvelopeFile(applicationContext))
        val accent = WardenThemePrefs.load(applicationContext)

        setContent {
            WardenTheme(accent = accent) {
                NetworkLogViewerScreen(
                    onRequestPresence = { onResult ->
                        presenceManager.request(
                            title = "Netzwerk-Log-Einsicht bestätigen",
                            subtitle = "Zugriff auf die strukturierten DNS-/Verbindungs-Ereignisse",
                        ) { result ->
                            when (result) {
                                is PresenceManager.Result.Success -> {
                                    val consumed = result.proof.consume()
                                    onResult(
                                        if (consumed) NetworkLogAccessOutcome.Granted(logStore.entries()) else NetworkLogAccessOutcome.Denied,
                                    )
                                }
                                PresenceManager.Result.Unavailable ->
                                    onResult(NetworkLogAccessOutcome.Unavailable)
                                PresenceManager.Result.Cancelled ->
                                    onResult(NetworkLogAccessOutcome.Denied)
                            }
                        }
                    },
                    onRequestPinPresence = { onResult ->
                        pendingPinPresenceResult = { granted ->
                            onResult(
                                if (granted) NetworkLogAccessOutcome.Granted(logStore.entries()) else NetworkLogAccessOutcome.Denied,
                            )
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

private sealed class NetworkLogAccessOutcome {
    data class Granted(val entries: List<NetworkLogEntry>) : NetworkLogAccessOutcome()
    data object Denied : NetworkLogAccessOutcome()
    data object Unavailable : NetworkLogAccessOutcome()
}

@Composable
private fun NetworkLogViewerScreen(
    onRequestPresence: ((NetworkLogAccessOutcome) -> Unit) -> Unit,
    onRequestPinPresence: ((NetworkLogAccessOutcome) -> Unit) -> Unit,
) {
    var outcome by remember { mutableStateOf<NetworkLogAccessOutcome?>(null) }
    var packageFilter by remember { mutableStateOf("") }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Netzwerk-Log", style = MaterialTheme.typography.headlineSmall)

            when (val current = outcome) {
                null -> {
                    Text(
                        "Presence-Nachweis erforderlich, bevor der Log-Inhalt angezeigt wird.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { onRequestPresence { result -> outcome = result } }) {
                        Text("Mit Biometrie bestätigen")
                    }
                    TextButton(onClick = { onRequestPinPresence { result -> outcome = result } }) {
                        Text("Mit Warden-PIN bestätigen")
                    }
                }
                NetworkLogAccessOutcome.Unavailable ->
                    Text(
                        "⚠ Keine Biometrie eingerichtet — Log-Einsicht nicht möglich.",
                        color = MaterialTheme.colorScheme.error,
                    )
                NetworkLogAccessOutcome.Denied ->
                    Text("Abgebrochen oder fehlgeschlagen — kein Log-Inhalt angezeigt.")
                is NetworkLogAccessOutcome.Granted -> {
                    OutlinedTextField(
                        value = packageFilter,
                        onValueChange = { packageFilter = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Nach Paketname filtern") },
                        singleLine = true,
                    )
                    val filtered = remember(current.entries, packageFilter) {
                        val needle = packageFilter.trim().lowercase()
                        current.entries.asReversed().filter {
                            needle.isEmpty() || it.packageName?.lowercase()?.contains(needle) == true
                        }
                    }
                    Text(
                        text = "${filtered.size} von ${current.entries.size} Ereignissen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(filtered) { entry ->
                            Text(
                                text = "${dateFormat.format(Date(entry.timestampMillis))} " +
                                    "[${entry.kind}] ${entry.packageName ?: "?"}: ${entry.detail}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
