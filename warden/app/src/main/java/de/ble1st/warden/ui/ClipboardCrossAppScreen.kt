package de.ble1st.warden.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ble1st.warden.R
import de.ble1st.warden.domain.clipboard.ClipboardAccessEvent
import de.ble1st.warden.ui.theme.mono
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CROSS_APP_TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault())

/**
 * Phase 2 ("Signal 2", `docs/design-clipboard-guard.md` Abschnitt 3.2.6/3.2.7) — eigener
 * Bildschirm statt Erweiterung der Dashboard-ClipboardGuard-Sektion, dieselbe Begründung wie bei
 * [PermissionAuditScreen]/[SecurityScannerScreen]: eigenständiger Datenbestand (Ereignisliste
 * statt eines einzelnen Zeitstempels) plus eine Erklärung, die Platz braucht und nicht in einer
 * Dashboard-Zeile verschwinden darf.
 *
 * **Die Aufklärungskarte ist dauerhaft sichtbar, kein Einmal-Dialog** — genau die vom Nutzer
 * gewählte Option (Abschnitt 5, Frage 4, Option 3: "voller Funktionsumfang, aber mit expliziter
 * UI-Aufklärung"), umgesetzt nach demselben Muster wie die FRP-/`isDebuggableOs`-Warnungen in
 * [StatusCard]: ein Dialog ließe sich einmal wegtippen und wäre danach vergessen, eine feste Karte
 * bleibt bei jedem Öffnen präsent, solange die Funktion aktiv ist.
 *
 * **Text-Vorschau ist standardmäßig maskiert, Antippen zeigt ihn** — reduziert nicht den
 * tatsächlichen Funktionsumfang (der Text wird unverändert erfasst und gespeichert, s.
 * [de.ble1st.warden.clipboard.ClipboardAccessController]), sondern nur, was beim bloßen Öffnen
 * des Bildschirms sofort sichtbar ist (Schulterblick-Schutz), dieselbe Zurückhaltung wie ein
 * Passwort-Feld mit Klarschrift-Umschalter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipboardCrossAppScreen(
    /** `null` = noch nicht geladen/Lesefehler — Schalter bleibt deaktiviert, s.
     * [de.ble1st.warden.ui.WardenStatusActivity]s übrige `null`-als-"noch nicht geladen"-Konvention. */
    monitoringEnabled: Boolean?,
    systemServiceEnabled: Boolean,
    events: List<ClipboardAccessEvent>?,
    onBack: () -> Unit,
    onToggleMonitoring: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onClearHistory: () -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.clipboard_cross_app_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.clipboard_cross_app_disclosure_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = stringResource(R.string.clipboard_cross_app_disclosure_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(R.string.clipboard_cross_app_toggle_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.clipboard_cross_app_toggle_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = monitoringEnabled == true,
                    enabled = monitoringEnabled != null,
                    onCheckedChange = onToggleMonitoring,
                )
            }

            Text(
                text = stringResource(
                    if (systemServiceEnabled) R.string.clipboard_cross_app_system_status_on else R.string.clipboard_cross_app_system_status_off,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (systemServiceEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onOpenAccessibilitySettings) {
                Text(stringResource(R.string.clipboard_cross_app_open_settings_action))
            }

            HorizontalDivider(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))

            SectionLabel(stringResource(R.string.clipboard_cross_app_history_label))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.clipboard_cross_app_refresh_action)) }
                TextButton(onClick = onClearHistory, enabled = !events.isNullOrEmpty()) {
                    Text(stringResource(R.string.clipboard_cross_app_clear_history_action))
                }
            }

            when {
                events == null -> EmptyStateRow(headline = stringResource(R.string.clipboard_cross_app_loading))
                events.isEmpty() -> EmptyStateRow(
                    headline = stringResource(R.string.clipboard_cross_app_empty_headline),
                    detail = stringResource(R.string.clipboard_cross_app_empty_detail),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(events, key = { it.timestampMillis.toString() + it.packageName }) { event ->
                        ClipboardAccessEventRow(event)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipboardAccessEventRow(event: ClipboardAccessEvent) {
    // rememberSaveable + stabiler key aus der LazyColumn (s. Aufrufer) — dieselbe Begründung wie
    // PermissionAuditRows expanded-Zustand: ohne das klappt eine aufgedeckte Zeile beim
    // Herausscrollen wieder zu.
    var revealed by rememberSaveable(event.timestampMillis, event.packageName) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = event.appLabel, style = MaterialTheme.typography.bodyLarge)
        Text(text = event.packageName, style = MaterialTheme.typography.bodySmall.mono())
        Text(
            text = CROSS_APP_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(event.timestampMillis)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (revealed) event.text else maskedPreview(event.text),
            style = MaterialTheme.typography.bodyMedium.mono(),
            modifier = Modifier
                .padding(top = 4.dp)
                .clickable { revealed = !revealed },
        )
        Text(
            text = stringResource(if (revealed) R.string.clipboard_cross_app_tap_to_hide else R.string.clipboard_cross_app_tap_to_reveal),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Bullet-Maskierung statt Textkürzung — verrät bewusst nicht einmal die ungefähre Wortstruktur,
 * nur die Zeichenlänge (auf ein Vielfaches von 8 gerundet, damit nicht jede exakte Länge selbst
 * schon ein Identifikationsmerkmal wird, z. B. eine sechsstellige PIN vs. ein langes Passwort). */
private fun maskedPreview(text: String): String {
    val roundedLength = ((text.length / 8) + 1) * 8
    return "•".repeat(roundedLength.coerceAtMost(64))
}
