package de.ble1st.warden.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ble1st.warden.R
import de.ble1st.warden.diagnostics.ReceiverDiagnostic
import de.ble1st.warden.diagnostics.SystemDiagnosticsSnapshot

/**
 * Ideenliste-Vorschlag 5 ("Systemdiagnose-Bildschirm", 2026-09-03) — s.
 * [de.ble1st.warden.diagnostics.SystemDiagnosticsReader]-Klassendoc für den Umfang. `snapshot ==
 * null` ist der Ladezustand (derselbe Bündel-Snapshot-statt-Einzelwerte-Ansatz wie
 * `WardenScreen.PerformanceMonitor`), nicht "nichts gefunden" — ein echter Lesefehler pro Eintrag
 * ist strukturell ausgeschlossen, da jeder Einzelwert in [de.ble1st.warden.diagnostics
 * .SystemDiagnosticsReader] bereits selbst `runCatching`/`getOrDefault(false)` absichert; ein
 * scheinbar "nicht eingeplanter" Worker ist also ein echter Befund, keine Lesestörung.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemDiagnosticsScreen(
    snapshot: SystemDiagnosticsSnapshot?,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.system_diagnostics_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (snapshot == null) {
                Text(stringResource(R.string.system_diagnostics_loading), style = MaterialTheme.typography.bodyMedium)
            } else {
                SectionLabel(stringResource(R.string.system_diagnostics_section_workers))
                Text(
                    stringResource(R.string.system_diagnostics_workers_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                snapshot.workers.forEach { worker -> DiagnosticRow(worker.label, worker.scheduled) }

                SectionLabel(stringResource(R.string.system_diagnostics_section_permissions))
                Text(
                    stringResource(R.string.system_diagnostics_permissions_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                snapshot.permissions.forEach { permission -> DiagnosticRow(permission.label, permission.granted) }

                SectionLabel(stringResource(R.string.system_diagnostics_section_receivers))
                AntiTheftReceiverRow(snapshot.antiTheftReceiver)

                TextButton(onClick = onRefresh, modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.system_diagnostics_refresh_action))
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = if (ok) stringResource(R.string.system_diagnostics_status_ok) else stringResource(R.string.system_diagnostics_status_missing),
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun AntiTheftReceiverRow(receiver: ReceiverDiagnostic) {
    // Anders als die reinen Worker-/Berechtigungszeilen: "nicht registriert" ist nur dann ein
    // Befund, wenn das Feature überhaupt eingeschaltet ist — bei ausgeschaltetem Diebstahlschutz
    // ist die Abmeldung genau das erwartete, korrekte Verhalten von `syncRegistration`.
    val ok = !receiver.featureEnabled || receiver.registered
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = receiver.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (receiver.featureEnabled) {
                    stringResource(R.string.system_diagnostics_receiver_feature_enabled)
                } else {
                    stringResource(R.string.system_diagnostics_receiver_feature_disabled)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (ok) stringResource(R.string.system_diagnostics_status_ok) else stringResource(R.string.system_diagnostics_status_missing),
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
