
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.ble1st.warden.R
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
                title = { Text(stringResource(R.string.network_title)) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NetLockdownToggleRow(active = lockdownActive, onToggle = onToggleLockdown)
            HorizontalDivider()
            Text(text = stringResource(R.string.network_app_access_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.network_app_access_intro),
                style = MaterialTheme.typography.bodySmall,
            )
            if (appsLoadFailed) {
                ErrorStateRow(
                    headline = stringResource(R.string.network_app_list_unreadable_headline),
                    detail = stringResource(R.string.network_no_device_owner_detail),
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
    val stateActive = stringResource(R.string.network_lockdown_state_active)
    val stateInactive = stringResource(R.string.network_lockdown_state_inactive)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics { stateDescription = if (active == true) stateActive else stateInactive },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = stringResource(R.string.network_lockdown_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.network_lockdown_description),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = active == true, onCheckedChange = onToggle, enabled = active != null)
    }
    if (active == null) {
        ErrorStateRow(
            headline = stringResource(R.string.network_lockdown_status_unreadable_headline),
            detail = stringResource(R.string.network_no_device_owner_detail),
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
            label = { Text(stringResource(R.string.network_search_label)) },
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
    val rowContentDescription = String.format(stringResource(R.string.network_app_row_content_description), app.label)
    val stateDirect = stringResource(R.string.network_app_row_state_direct)
    val stateStandard = stringResource(R.string.network_app_row_state_standard)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = rowContentDescription
                stateDescription = if (mode == FirewallMode.ALLOWED) stateDirect else stateStandard
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
        Text(text = stringResource(R.string.network_blocklist_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = String.format(stringResource(R.string.network_blocklist_summary), defaultSize, userDomains.size),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.network_add_domain_label)) },
                singleLine = true,
            )
            TextButton(
                onClick = {
                    onAddDomain(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) { Text(stringResource(R.string.network_add_domain_action)) }
        }
        userDomains.sorted().forEach { domain ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = domain, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemoveDomain(domain) }) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = String.format(stringResource(R.string.network_remove_domain_content_description), domain))
                }
            }
        }
    }
}
