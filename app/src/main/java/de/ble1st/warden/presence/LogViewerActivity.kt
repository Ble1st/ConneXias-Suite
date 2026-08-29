package de.ble1st.warden.presence

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import de.ble1st.warden.domain.appmanagement.ThreatSeverity
import de.ble1st.warden.domain.securitylog.SecurityLogCodec
import de.ble1st.warden.logging.LogEntry
import de.ble1st.warden.logging.SecurityEventStore
import de.ble1st.warden.wardenSecurityEvents
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import de.ble1st.warden.wardenAuditLog
import de.ble1st.warden.ui.theme.WardenTheme
import de.ble1st.warden.ui.theme.WardenThemePrefs
import de.ble1st.warden.ui.theme.mono

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
 * **Zwei Ansichten seit 2026-08-28** ("System-Ereignisprotokoll"): Wardens eigenes,
 * hash-verkettetes Audit-Log wie bisher — und neu die vom System gemeldeten Sicherheits- und
 * Netzwerkereignisse ([de.ble1st.warden.logging.SecurityEventStore]), die vorher abgerufen und
 * sofort wieder verworfen wurden. Bewusst **hinter demselben Presence-Gate** statt als eigener
 * Bildschirm: der Inhalt (adb-Kommandozeilen, installierte Pakete, aufgelöste Hostnamen) ist
 * mindestens so aussagekräftig wie das Audit-Log, ein zweiter, schwächer geschützter Zugang dazu
 * wäre ein Rückschritt.
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
        val securityEventStore = wardenSecurityEvents(applicationContext)
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
                                    if (consumed) {
                                        loadGrantedOffMainThread(logStore, securityEventStore, onResult)
                                    } else {
                                        onResult(LogAccessOutcome.Denied)
                                    }
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
                            if (granted) {
                                loadGrantedOffMainThread(logStore, securityEventStore, onResult)
                            } else {
                                onResult(LogAccessOutcome.Denied)
                            }
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

    /**
     * Beide Presence-Wege (Biometrie-Callback und PIN-Rückgabe) laufen auf dem Main-Thread — das
     * eigentliche Laden gehört dort nicht hin (2026-08-28, Befund Q-3, s.
     * [LogAccessOutcome.Granted.from]). `lifecycleScope` statt eines eigenen Scopes, damit ein
     * Wegdrehen der Activity das Laden mit beendet, statt ein Ergebnis an eine tote UI zu liefern.
     */
    private fun loadGrantedOffMainThread(
        logStore: HashChainLogStore,
        securityEventStore: SecurityEventStore,
        onResult: (LogAccessOutcome) -> Unit,
    ) {
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                LogAccessOutcome.Granted.from(logStore, securityEventStore)
            }
            onResult(outcome)
        }
    }
}

private sealed class LogAccessOutcome {
    data class Granted(
        val entries: List<LogEntry>,
        val chain: ChainVerificationResult,
        val systemEvents: SecurityLogCodec.DecodeResult,
        val discardedThroughSequence: Long?,
    ) : LogAccessOutcome() {
        companion object {
            /**
             * Ein Lesefehler des System-Ereignisprotokolls darf die Audit-Log-Einsicht nicht
             * verhindern — beides sind unabhängige Dateien, und der Presence-Nachweis wurde für
             * beide gleichermaßen erbracht. Der Fehlerfall wird als leeres Ergebnis mit
             * `skippedLines = -1` sichtbar gemacht statt still verschluckt.
             *
             * **Muss auf [Dispatchers.IO] laufen** (2026-08-28, Befund Q-3): entschlüsselt jedes
             * Archivsegment plus bis zu 2000 Systemereignisse. Vorher lief das im
             * Biometrie-Callback auf dem Main-Executor — und zusätzlich doppelt, weil
             * `verifyChainIntegrity()` die Kette intern noch einmal von der Platte las. Jetzt wird
             * einmal geladen und die geladene Liste an [HashChainLogStore.verifyLoadedChain]
             * gereicht.
             */
            fun from(logStore: HashChainLogStore, securityEventStore: SecurityEventStore): Granted {
                val entries = logStore.entries()
                val systemEvents = runCatching { securityEventStore.read() }
                    .getOrElse { SecurityLogCodec.DecodeResult(emptyList(), -1) }
                return Granted(
                    entries = entries,
                    chain = logStore.verifyLoadedChain(entries),
                    systemEvents = systemEvents,
                    discardedThroughSequence = runCatching { logStore.discardedThroughSequence() }.getOrNull(),
                )
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
    var showSystemEvents by remember { mutableStateOf(false) }

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
                is LogAccessOutcome.Granted -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showSystemEvents = false }) {
                            Text(if (showSystemEvents) "Warden-Audit" else "▸ Warden-Audit")
                        }
                        TextButton(onClick = { showSystemEvents = true }) {
                            Text(
                                if (showSystemEvents) {
                                    "▸ System (${current.systemEvents.records.size})"
                                } else {
                                    "System (${current.systemEvents.records.size})"
                                },
                            )
                        }
                    }
                    if (showSystemEvents) {
                        SystemEventList(current.systemEvents)
                    } else {
                        AuditLogList(current)
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditLogList(granted: LogAccessOutcome.Granted) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Text(
                text = when (val chain = granted.chain) {
                    is ChainVerificationResult.Valid -> "Kette gültig (${chain.entryCount} Einträge)"
                    is ChainVerificationResult.Broken -> "⚠ Kette gebrochen bei #${chain.atSequence}: ${chain.reason}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (granted.chain is ChainVerificationResult.Broken) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        // Aufbewahrungsgrenze (2026-08-28, Befund Q-3): dass die Liste nicht bei #0 anfängt, muss
        // hier erklärt dastehen — sonst sieht ein regulär verworfener Anfang für die Betreiberin
        // genauso aus wie ein gelöschter, und genau diese Unterscheidung ist der Zweck der Kette.
        granted.discardedThroughSequence?.let { sequence ->
            item {
                Text(
                    text = "Ältere Einträge bis #$sequence wurden nach der Aufbewahrungsgrenze " +
                        "verworfen; die Kette schließt lückenlos daran an.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(granted.entries.asReversed()) { entry ->
            Text(
                text = "#${entry.sequence} [${entry.tag}] ${entry.message}",
                // Vorschlag U-9 (2026-08-29): body* ist seither proportional — die Protokollzeilen
                // fordern Monospace gezielt an. Hier hat feste Zeichenbreite eine Funktion:
                // Sequenznummern und Tags stehen dadurch über die Zeilen hinweg untereinander.
                style = MaterialTheme.typography.bodySmall.mono(),
            )
        }
    }
}

/**
 * Die vom System gemeldeten Ereignisse (2026-08-28). Voreingestellt ist der Filter "ab Warnung":
 * mit eingeschaltetem Netzwerk-Logging bestehen über 90 % der Einträge aus DNS-Auflösungen — eine
 * ungefilterte Liste wäre zwar vollständig, aber unbrauchbar, um einen adb-Zugriff oder eine
 * nachträglich installierte Zertifizierungsstelle zu finden.
 */
@Composable
private fun SystemEventList(decoded: SecurityLogCodec.DecodeResult) {
    var minimumSeverity by remember { mutableStateOf(ThreatSeverity.WARNING) }
    val visible = decoded.records.filter { it.severity.ordinal >= minimumSeverity.ordinal }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (severity in ThreatSeverity.entries) {
            TextButton(onClick = { minimumSeverity = severity }) {
                Text(
                    text = when (severity) {
                        ThreatSeverity.INFO -> "Alle"
                        ThreatSeverity.WARNING -> "Ab Warnung"
                        ThreatSeverity.CRITICAL -> "Nur kritisch"
                    },
                    color = if (severity == minimumSeverity) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }

    when {
        decoded.skippedLines < 0 -> Text(
            "⚠ System-Ereignisprotokoll nicht lesbar — Datei beschädigt oder Schlüssel verloren.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        decoded.records.isEmpty() -> Text(
            "Keine Ereignisse gespeichert. System-Sicherheitslog und/oder Netzwerk-Metadaten-Log " +
                "müssen unter Safeguards ▸ Forensik/Audit eingeschaltet sein; das System liefert " +
                "die Ereignisse danach schubweise, nicht sofort.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (decoded.skippedLines > 0) {
                item {
                    Text(
                        "⚠ ${decoded.skippedLines} beschädigte Zeile(n) übersprungen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                Text(
                    "${visible.size} von ${decoded.records.size} Ereignissen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(visible.asReversed()) { record ->
                Text(
                    text = "${formatTimestamp(record.timestampMillis)} ${severityMarker(record.severity)} " +
                        "${record.type.label}: ${record.detail}",
                    // Monospace wie bei den Audit-Zeilen (Vorschlag U-9): Zeitstempel und
                    // Schweregrad-Marker fluchten nur bei fester Zeichenbreite.
                    style = MaterialTheme.typography.bodySmall.mono(),
                    color = when (record.severity) {
                        ThreatSeverity.CRITICAL -> MaterialTheme.colorScheme.error
                        ThreatSeverity.WARNING -> MaterialTheme.colorScheme.onSurface
                        ThreatSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun severityMarker(severity: ThreatSeverity): String = when (severity) {
    ThreatSeverity.CRITICAL -> "‼"
    ThreatSeverity.WARNING -> "⚠"
    ThreatSeverity.INFO -> "·"
}

// Jahr bewusst im Format (2026-08-28, Live-Bug-Fund): ein Zeitstempel-Fehler in
// SecurityEventParser hatte Ereignisse mit einem 57 Jahre falschen Datum angezeigt — unbemerkt,
// weil das vorherige Format "dd.MM. HH:mm:ss" das Jahr verschluckte und ein falsches Zukunftsjahr
// wie ein plausibles Tag/Monat-Datum aus der jüngeren Vergangenheit aussehen ließ.
private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault())

private fun formatTimestamp(millis: Long): String =
    runCatching { TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(millis)) }
        .getOrDefault("??.??.???? ??:??:??")
