package de.ble1st.warden.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.ble1st.warden.R
import de.ble1st.warden.appmanagement.PermissionAuditInfo
import de.ble1st.warden.domain.appmanagement.PermissionAuditDecision
import de.ble1st.warden.ui.theme.mono

/**
 * "Detaillierte Permission-Audit-Reports" (2026-08-25, Feature-Ideenliste Punkt 22). Eigener
 * Bildschirm statt Erweiterung von [SecurityScannerScreen] — anderes Modell (jede installierte
 * App bekommt eine Zeile, nicht nur Verdachtsfunde) und eigener, teurer Scan-Pfad (s.
 * [de.ble1st.warden.appmanagement.PermissionAuditScanner]-Klassendoc), deshalb bewusst nicht
 * automatisch beim Öffnen, sondern über einen expliziten "Scannen"-Button wie
 * [SecurityScannerScreen]s "Jetzt scannen" (Feature 10) — nur ohne den zusätzlichen
 * Pull-to-Refresh-Weg, dieser Bildschirm hat keinen laufenden Hintergrund-Scanner, dessen Ergebnis
 * "aktuell gehalten" werden müsste.
 *
 * Seit 2026-08-29 trägt jede Zeile mit gefährlichen Rechten zusätzlich einen manuellen
 * "Gefährliche Rechte sperren"/"wiederherstellen"-Knopf (Feature 3 "Permission Auto-Block" aus
 * `docs/umsetzungsplan-7-features.md`) — die automatische Durchsetzung existierte bereits vorher
 * für Verdachtsscanner-Funde ([de.ble1st.warden.appmanagement.SuspiciousAppScanController.enforce]),
 * dieser Bildschirm macht denselben Mechanismus manuell für jede beliebige Fremd-App erreichbar,
 * ohne dass sie erst als Fund auffallen muss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionAuditScreen(
    findings: List<PermissionAuditInfo>?,
    scanInProgress: Boolean,
    revokedPackages: Set<String>,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onRevoke: (String) -> Unit,
    onRestore: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permission_audit_title)) },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = String.format(stringResource(R.string.permission_audit_intro), PermissionAuditDecision.THRESHOLD),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onScan, enabled = !scanInProgress) {
                    Text(if (findings == null) stringResource(R.string.permission_audit_scan_action) else stringResource(R.string.permission_audit_rescan_action))
                }
                if (scanInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            // Vorschlag V-9 (2026-08-29): Auffällige zuerst, dann nach Anzahl gefährlicher Rechte,
            // dann nach Namen. Vorher stand die Liste in Scanner-Reihenfolge — auf einem realen
            // Gerät sind das dreistellig viele Zeilen, und genau die eine App, wegen der dieser
            // Bildschirm existiert, konnte irgendwo mittendrin stehen. Die Namenssortierung als
            // letztes Kriterium hält die Reihenfolge zwischen zwei Scans stabil.
            var onlyFlagged by rememberSaveable { mutableStateOf(false) }
            val visible = remember(findings, onlyFlagged) {
                findings
                    ?.filter { !onlyFlagged || it.tooManyDangerousPermissions }
                    ?.sortedWith(
                        compareByDescending<PermissionAuditInfo> { it.tooManyDangerousPermissions }
                            .thenByDescending { it.dangerousPermissions.size }
                            .thenBy { it.label.lowercase() },
                    )
            }
            if (findings != null) {
                val filterStateOn = stringResource(R.string.security_scanner_autofreeze_state_on)
                val filterStateOff = stringResource(R.string.security_scanner_autofreeze_state_off)
                val filterContentDescription = String.format(
                    stringResource(R.string.permission_audit_filter_content_description),
                    visible?.size ?: 0,
                    findings.size,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = onlyFlagged,
                            role = Role.Switch,
                            onValueChange = { onlyFlagged = it },
                        )
                        .padding(vertical = 4.dp)
                        .semantics {
                            stateDescription = if (onlyFlagged) filterStateOn else filterStateOff
                            contentDescription = filterContentDescription
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(text = stringResource(R.string.permission_audit_filter_label), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = String.format(stringResource(R.string.permission_audit_visible_count), visible?.size ?: 0, findings.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = onlyFlagged, onCheckedChange = null)
                }
            }
            HorizontalDivider()
            when {
                findings == null && !scanInProgress ->
                    EmptyStateRow(headline = stringResource(R.string.permission_audit_not_scanned_headline), detail = stringResource(R.string.permission_audit_not_scanned_detail))
                findings == null -> {}
                findings.isEmpty() -> EmptyStateRow(headline = stringResource(R.string.permission_audit_no_apps_headline))
                visible.isNullOrEmpty() ->
                    EmptyStateRow(
                        headline = stringResource(R.string.permission_audit_none_flagged_headline),
                        detail = String.format(
                            stringResource(R.string.permission_audit_none_flagged_detail),
                            findings.size,
                            PermissionAuditDecision.THRESHOLD,
                        ),
                    )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(visible, key = { it.packageName }) { info ->
                        PermissionAuditRow(
                            info = info,
                            revoked = info.packageName in revokedPackages,
                            onRevoke = { onRevoke(info.packageName) },
                            onRestore = { onRestore(info.packageName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionAuditRow(
    info: PermissionAuditInfo,
    revoked: Boolean,
    onRevoke: () -> Unit,
    onRestore: () -> Unit,
) {
    // rememberSaveable statt remember (Vorschlag V-10, 2026-08-29): eine aufgeklappte Zeile in
    // einer LazyColumn verliert ihren Zustand, sobald sie aus dem Sichtfenster scrollt — beim
    // Zurückscrollen war sie wieder zu. Mit dem `key = { it.packageName }` der LazyColumn hat
    // jede Zeile einen stabilen Speicherplatz.
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rowContentDescription = String.format(
        stringResource(R.string.permission_audit_row_content_description),
        info.label,
        info.packageName,
        info.dangerousPermissions.size,
        info.specialPermissions.size,
    )
    val rowStateFlagged = stringResource(R.string.permission_audit_row_state_flagged)
    val rowStateUnremarkable = stringResource(R.string.permission_audit_row_state_unremarkable)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            // Vorschlag V-10 (2026-08-29) — dieselbe Korrektur wie V-5 in der App-Verwaltung und
            // der Fundliste: ohne `mergeDescendants` tritt die Beschreibung neben die
            // Kinderknoten statt sie zu ersetzen, und eine Vorlesehilfe las App-Name und
            // Paketnamen anschließend noch einmal einzeln. Die Rechte-Zahlen kommen mit in die
            // Beschreibung — sie sind die eigentliche Information dieser Zeile.
            .semantics(mergeDescendants = true) {
                contentDescription = rowContentDescription
                stateDescription = if (info.tooManyDangerousPermissions) rowStateFlagged else rowStateUnremarkable
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(text = info.label, style = MaterialTheme.typography.bodyLarge)
                Text(text = info.packageName, style = MaterialTheme.typography.bodySmall.mono())
                Text(
                    text = String.format(stringResource(R.string.permission_audit_row_summary), info.dangerousPermissions.size, info.specialPermissions.size) +
                        if (revoked) stringResource(R.string.permission_audit_row_revoked_suffix) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        revoked -> MaterialTheme.colorScheme.error
                        info.tooManyDangerousPermissions -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) stringResource(R.string.permission_audit_less_action) else stringResource(R.string.permission_audit_details_action))
            }
        }
        if (expanded) {
            if (info.dangerousPermissions.isNotEmpty()) {
                Text(
                    text = String.format(stringResource(R.string.permission_audit_dangerous_prefix), info.dangerousPermissions.joinToString { it.substringAfterLast('.') }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // Feature 3 "Permission Auto-Block" (2026-08-29): manueller Baustein neben der
            // bereits bestehenden automatischen Durchsetzung in
            // SuspiciousAppScanController.enforce() — hier kann der Nutzer eine beliebige
            // Fremd-App sperren, ohne dass sie erst als Verdachtsfund auffallen muss.
            // Systemapps sind aus der Liste bereits ausgeschlossen (s. Screen-Klassendoc), ein
            // Entzug bei sich selbst ist also strukturell nicht erreichbar. Kein zusätzliches
            // Bestätigungsdialog nötig: DevicePolicyManager.setPermissionGrantState ist jederzeit
            // rückgängig zu machen (kein WIPE_DATA-artiger Point of no return), und dieser
            // Bildschirm ist ohnehin nur über den WardenLock-Gate der App erreichbar.
            if (info.dangerousPermissions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (revoked) {
                        TextButton(onClick = onRestore) { Text(stringResource(R.string.permission_audit_restore_action)) }
                    } else {
                        TextButton(onClick = onRevoke) { Text(stringResource(R.string.permission_audit_revoke_action)) }
                    }
                }
            }
            if (info.specialPermissions.isNotEmpty()) {
                Text(
                    text = String.format(stringResource(R.string.permission_audit_special_prefix), info.specialPermissions.joinToString { it.substringAfterLast('.') }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
