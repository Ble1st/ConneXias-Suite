// ⏸ PAUSIERT (2026-08-27): "Netz-Sperre" ist vorübergehend deaktiviert — Live-Test auf dem
// physischen Testgerät fand nach mehreren echten Bugfixes (siehe Commit 7252396 und
// warden-netzsperre-feature-2026-08-27-Memo) einen weiterhin ungeklärten Kernfehler: die
// DNS-Blockliste/NAT-Relay verarbeitet auf einem frisch aufgebauten Tunnel keinen Traffic mehr,
// Ursache unbekannt. Diese Datei liegt deshalb bewusst außerhalb jedes Gradle-Source-Sets
// (app/netlock-disabled/ statt app/src/main/java/) — wird NICHT mitkompiliert, ist nirgendwo
// verkabelt. Zum Reaktivieren: Verzeichnis zurück nach app/src/main/java/... verschieben, alle
// Wiederverkabelungsstellen aus dem Deaktivierungs-Commit rückgängig machen (siehe dessen
// Commit-Message für die vollständige Liste), Kernfehler zuerst klären.

package de.ble1st.warden.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import de.ble1st.warden.appmanagement.InstalledAppEntry
import de.ble1st.warden.netlock.FirewallMode

/**
 * "Netz-Sperre" (2026-08-27) — UI-Fassade in drei Abschnitten (s. Plan Abschnitt 4): Ein/Aus-
 * Schalter, App-Zugriff (wiederverwendet [InstalledAppEntry] wie [AppManagementScreen]), Blockliste.
 * Bewusst **ein** Bildschirm statt drei — anders als App-Verwaltung/Sicherheits-Scanner ist keiner
 * der drei Abschnitte für sich genommen so umfangreich, dass er einen eigenen `WardenScreen`-Zweig
 * rechtfertigt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    lockdownActive: Boolean?,
    onToggleLockdown: (Boolean) -> Unit,
    apps: List<InstalledAppEntry>,
    appsLoadFailed: Boolean,
    modeFor: (String) -> FirewallMode,
    onSetMode: (packageName: String, mode: FirewallMode) -> Unit,
    userBlocklistDomains: Set<String>,
    defaultBlocklistSize: Int,
    onAddDomain: (String) -> Unit,
    onRemoveDomain: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Netzwerk") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NetLockdownToggleRow(active = lockdownActive, onToggle = onToggleLockdown)
            HorizontalDivider()
            Text(text = "App-Zugriff", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Direktzugriff = App geht am Tunnel komplett vorbei (ungefiltertes Internet). " +
                    "Standard = App läuft durch die Netz-Sperre, DNS-Anfragen werden gegen die " +
                    "Blockliste geprüft.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (appsLoadFailed) {
                ErrorStateRow(
                    headline = "App-Liste konnte nicht geladen werden",
                    detail = "Vermutlich kein Device Owner aktiv.",
                )
            }
            NetworkAppList(apps = apps, modeFor = modeFor, onSetMode = onSetMode, modifier = Modifier.weight(1f))
            HorizontalDivider()
            BlocklistSection(
                userDomains = userBlocklistDomains,
                defaultSize = defaultBlocklistSize,
                onAddDomain = onAddDomain,
                onRemoveDomain = onRemoveDomain,
            )
        }
    }
}

@Composable
private fun NetLockdownToggleRow(active: Boolean?, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics { stateDescription = if (active == true) "scharf" else "entschärft" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = "Netz-Sperre", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Kill-Switch + DNS-Blockliste für den gesamten Gerätetraffic (Always-On-VPN, " +
                    "Device-Owner-erzwungen).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = active == true, onCheckedChange = onToggle, enabled = active != null)
    }
    if (active == null) {
        ErrorStateRow(
            headline = "Status konnte nicht gelesen werden",
            detail = "Vermutlich kein Device Owner aktiv.",
        )
    }
}

@Composable
private fun NetworkAppList(
    apps: List<InstalledAppEntry>,
    modeFor: (String) -> FirewallMode,
    onSetMode: (packageName: String, mode: FirewallMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    val filtered = remember(apps, query, showSystemApps) {
        val needle = query.trim().lowercase()
        apps.filter { app ->
            (showSystemApps || !app.isSystemApp) &&
                (
                    needle.isEmpty() ||
                        app.label.lowercase().contains(needle) ||
                        app.packageName.lowercase().contains(needle)
                    )
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SystemAppFilterRow(showSystemApps = showSystemApps, onShowSystemAppsChange = { showSystemApps = it })
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Suchen") },
            singleLine = true,
        )
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filtered, key = { it.packageName }) { app ->
                NetworkAppRow(app = app, mode = modeFor(app.packageName), onSetMode = { onSetMode(app.packageName, it) })
            }
        }
    }
}

@Composable
private fun NetworkAppRow(app: InstalledAppEntry, mode: FirewallMode, onSetMode: (FirewallMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = "Netzwerk-Zugriff ${app.label}"
                stateDescription = if (mode == FirewallMode.ALLOWED) "Direktzugriff" else "Standard"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
            Text(text = app.packageName, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = mode == FirewallMode.ALLOWED,
            onCheckedChange = { allowed -> onSetMode(if (allowed) FirewallMode.ALLOWED else FirewallMode.CAPTURED) },
        )
    }
}

@Composable
private fun BlocklistSection(
    userDomains: Set<String>,
    defaultSize: Int,
    onAddDomain: (String) -> Unit,
    onRemoveDomain: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Blockliste", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "$defaultSize eingebaute Tracker-/Ad-Domains + ${userDomains.size} eigene. " +
                "DNS-Anfragen an gesperrte Domains (und ihre Subdomains) werden mit NXDOMAIN beantwortet.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                label = { Text("Domain hinzufügen") },
                singleLine = true,
            )
            TextButton(
                onClick = {
                    onAddDomain(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) { Text("Hinzufügen") }
        }
        userDomains.sorted().forEach { domain ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = domain, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemoveDomain(domain) }) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "$domain entfernen")
                }
            }
        }
    }
}
