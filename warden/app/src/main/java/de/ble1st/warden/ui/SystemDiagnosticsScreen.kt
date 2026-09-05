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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ble1st.warden.R
import de.ble1st.warden.diagnostics.AdminCoexistenceDiagnostic
import de.ble1st.warden.diagnostics.ReceiverDiagnostic
import de.ble1st.warden.diagnostics.SystemDiagnosticsSnapshot
import de.ble1st.warden.diagnostics.TheftProtectionDiagnostic
import de.ble1st.warden.ui.theme.mono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

                SectionLabel(stringResource(R.string.system_diagnostics_section_coexistence))
                AdminCoexistenceSection(snapshot.adminCoexistence)

                SectionLabel(stringResource(R.string.system_diagnostics_section_theft_protection))
                TheftProtectionSection(snapshot.theftProtection)

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

/**
 * Tier 3 der DPC-Recherche (2026-09-05): zeigt, wer sonst noch Richtlinien auf diesem Gerät setzt
 * und ob eine von Warden gesetzte Richtlinie dabei unter die Räder gekommen ist — s.
 * [de.ble1st.warden.admin.WardenPolicyUpdateReceiver] für die Herkunft der Meldungen.
 */
@Composable
private fun AdminCoexistenceSection(diagnostic: AdminCoexistenceDiagnostic) {
    Text(
        stringResource(R.string.system_diagnostics_coexistence_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    DiagnosticRow(stringResource(R.string.system_diagnostics_coexistence_device_owner), diagnostic.wardenIsDeviceOwner)

    if (diagnostic.otherActiveAdmins.isEmpty()) {
        Text(
            stringResource(R.string.system_diagnostics_coexistence_no_other_admins),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    } else {
        diagnostic.otherActiveAdmins.forEach { admin ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(text = admin.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    // Der Komponentenname ist die Angabe, mit der sich derselbe Admin in
                    // `adb shell dumpsys device_policy` wiederfinden lässt — deshalb monospace,
                    // dieselbe Konvention wie bei Paketnamen im Rest der App.
                    text = admin.componentName,
                    style = MaterialTheme.typography.bodySmall.mono(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    when {
        // Reihenfolge zählt: "noch keine Rückmeldung" darf nie als "keine Konflikte" durchgehen.
        !diagnostic.policyFeedbackReceived -> Text(
            stringResource(R.string.system_diagnostics_coexistence_no_feedback_yet),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        diagnostic.policyProblems.isEmpty() -> Text(
            stringResource(R.string.system_diagnostics_coexistence_no_problems),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        else -> diagnostic.policyProblems.forEach { problem ->
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    text = problem.policyIdentifier,
                    style = MaterialTheme.typography.bodyMedium.mono(),
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "${problem.outcome.label} · ${formatTimestamp(problem.timestampMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** s. [TheftProtectionDiagnostic]-Klassendoc: bewusst nur ein lesbarer Wert plus ein Verweis,
 * kein geratener Status. */
@Composable
private fun TheftProtectionSection(diagnostic: TheftProtectionDiagnostic) {
    val context = LocalContext.current
    Text(
        stringResource(R.string.system_diagnostics_theft_protection_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    DiagnosticRow(stringResource(R.string.system_diagnostics_theft_protection_device_secure), diagnostic.deviceSecure)
    TextButton(onClick = { openSecuritySettings(context) }) {
        Text(stringResource(R.string.system_diagnostics_theft_protection_open_settings))
    }
}

private fun openSecuritySettings(context: android.content.Context) {
    // Wie `openReleasesPage` in SettingsScreen: der Intent kann auf einem gehärteten Gerät ins
    // Leere gehen — das darf einen reinen Diagnosebildschirm nicht abstürzen lassen.
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(millis))
