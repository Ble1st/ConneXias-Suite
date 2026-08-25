package de.ble1st.warden.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.ble1st.warden.appmanagement.PermissionAuditInfo
import de.ble1st.warden.domain.appmanagement.PermissionAuditDecision

/**
 * "Detaillierte Permission-Audit-Reports" (2026-08-25, Feature-Ideenliste Punkt 22). Eigener
 * Bildschirm statt Erweiterung von [SecurityScannerScreen] — anderes Modell (jede installierte
 * App bekommt eine Zeile, nicht nur Verdachtsfunde) und eigener, teurer Scan-Pfad (s.
 * [de.ble1st.warden.appmanagement.PermissionAuditScanner]-Klassendoc), deshalb bewusst nicht
 * automatisch beim Öffnen, sondern über einen expliziten "Scannen"-Button wie
 * [SecurityScannerScreen]s "Jetzt scannen" (Feature 10) — nur ohne den zusätzlichen
 * Pull-to-Refresh-Weg, dieser Bildschirm hat keinen laufenden Hintergrund-Scanner, dessen Ergebnis
 * "aktuell gehalten" werden müsste.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionAuditScreen(
    findings: List<PermissionAuditInfo>?,
    scanInProgress: Boolean,
    onBack: () -> Unit,
    onScan: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permission-Audit") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Klassifiziert die von jeder installierten Fremd-App deklarierten Rechte " +
                    "(Normal/Gefährlich/Speziell) und warnt ab ${PermissionAuditDecision.THRESHOLD} " +
                    "gleichzeitig deklarierten gefährlichen Rechten. Systemapps sind ausgenommen.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onScan, enabled = !scanInProgress) {
                    Text(if (findings == null) "Scannen" else "Erneut scannen")
                }
                if (scanInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            HorizontalDivider()
            when {
                findings == null && !scanInProgress ->
                    EmptyStateRow(headline = "Noch nicht gescannt", detail = "Auf \"Scannen\" tippen.")
                findings == null -> {}
                findings.isEmpty() -> EmptyStateRow(headline = "Keine Fremd-Apps gefunden")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(findings, key = { it.packageName }) { info ->
                        PermissionAuditRow(info)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionAuditRow(info: PermissionAuditInfo) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics {
                contentDescription = "Permission-Audit ${info.label}"
                stateDescription = if (info.tooManyDangerousPermissions) "zu viele gefährliche Rechte" else "unauffällig"
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(text = info.label, style = MaterialTheme.typography.bodyLarge)
                Text(text = info.packageName, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "${info.dangerousPermissions.size} gefährlich · ${info.specialPermissions.size} speziell",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (info.tooManyDangerousPermissions) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Weniger" else "Details")
            }
        }
        if (expanded) {
            if (info.dangerousPermissions.isNotEmpty()) {
                Text(
                    text = "Gefährlich: " + info.dangerousPermissions.joinToString { it.substringAfterLast('.') },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (info.specialPermissions.isNotEmpty()) {
                Text(
                    text = "Speziell: " + info.specialPermissions.joinToString { it.substringAfterLast('.') },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
