
package de.ble1st.warden.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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
    /** `null` = nicht konfiguriert, sonst "host:port" für die Anzeige — s. `ChildVpnSection`. */
    childVpnConfiguredEndpoint: String?,
    onApplyChildVpnConfig: (String) -> Unit,
    onRemoveChildVpnConfig: () -> Unit,
    childVpnError: String?,
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
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
            // Feste Maximalhöhe statt `weight(1f)`: der äußere Screen ist jetzt selbst
            // scrollbar (s. `.verticalScroll` oben, Fix für "netzwerkui ist nicht
            // scrollable" — die neue ChildVpnSection sprengte auf kleineren Screens die
            // feste Bildschirmhöhe, und der äußere Column konnte selbst nicht scrollen).
            // Ein `weight(1f)`-Kind setzt eine endliche Eltern-Höhe voraus, die ein
            // `verticalScroll`-Column nicht mehr liefert (unendliche Höhe) — die Liste
            // bleibt trotzdem eigenständig scrollbar (LazyColumn mit begrenzter Höhe,
            // verschachteltes Scrollen funktioniert dafür problemlos).
            NetworkAppList(apps = apps, modeFor = modeFor, onSetMode = onSetMode, modifier = Modifier.heightIn(max = 360.dp))
            HorizontalDivider()
            BlocklistSection(
                userDomains = userBlocklistDomains,
                defaultSize = defaultBlocklistSize,
                onAddDomain = onAddDomain,
                onRemoveDomain = onRemoveDomain,
            )
            HorizontalDivider()
            ChildVpnSection(
                configuredEndpoint = childVpnConfiguredEndpoint,
                onApply = onApplyChildVpnConfig,
                onRemove = onRemoveChildVpnConfig,
                error = childVpnError,
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

/**
 * ChildVPN (2026-08-31, `docs/design-barbican-prozess-childvpn.md`) — wg-quick-Konfigurationstext
 * einfügen statt eines eigenen Formulars (Nutzer-Entscheidung, s. Design-Dok). Reine Anzeige/
 * Eingabe hier; das Parsen ([de.ble1st.warden.domain.netlock.ChildVpnConfigParser]) und die
 * eigentliche Speicherung/Aktivierung passieren im Aufrufer ([onApply]) — dieser Screen bleibt
 * bewusst framework-/Rust-Engine-frei wie jeder andere Screen in dieser Datei.
 *
 * **QR-Scan (2026-08-31, Nutzeranforderung, korrigiert eine frühere "bewusst nicht gebaut"-
 * Entscheidung im Design-Dok):** `com.journeyapps:zxing-android-embedded` liefert dieselbe rohe
 * Textform, die auch der Paste-Pfad erwartet — jedes gängige WireGuard-Setup-Skript kodiert den
 * kompletten wg-quick-Konfigurationstext 1:1 in den QR-Code (derselbe Standard, den die offizielle
 * WireGuard-App für "Aus QR-Code erstellen" nutzt), deshalb genügt es, das Scan-Ergebnis in [draft]
 * zu schreiben — [de.ble1st.warden.domain.netlock.ChildVpnConfigParser] selbst musste dafür nicht
 * angefasst werden, genau wie ursprünglich im Design-Dok vermerkt. Ein erfolgreicher Scan füllt nur
 * das Textfeld, übernimmt NICHT automatisch — derselbe "erst sichtbar machen, dann bewusst
 * bestätigen"-Schritt wie beim manuellen Einfügen, wichtig weil hier echtes Schlüsselmaterial durch
 * die Luft kommt. CAMERA ist eine echte, an diesen einen Tap gebundene Laufzeit-Berechtigungsanfrage
 * (kein DPM-Self-Grant, s. Manifest-Kommentar) — eine Ablehnung zeigt eine eigene Fehlerzeile statt
 * still nichts zu tun.
 */
@Composable
private fun ChildVpnSection(
    configuredEndpoint: String?,
    onApply: (String) -> Unit,
    onRemove: () -> Unit,
    error: String?,
) {
    var draft by remember { mutableStateOf("") }
    var cameraPermissionDenied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scanPrompt = stringResource(R.string.network_child_vpn_scan_prompt)

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { draft = it }
    }
    fun launchScan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(scanPrompt)
                .setBeepEnabled(false)
                .setOrientationLocked(true),
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionDenied = !granted
        if (granted) launchScan()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.network_child_vpn_title), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.network_child_vpn_intro), style = MaterialTheme.typography.bodySmall)
        Text(
            text = if (configuredEndpoint != null) {
                String.format(stringResource(R.string.network_child_vpn_configured_summary), configuredEndpoint)
            } else {
                stringResource(R.string.network_child_vpn_not_configured_summary)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (error != null) {
            ErrorStateRow(
                headline = String.format(stringResource(R.string.network_child_vpn_parse_error), error),
                detail = "",
            )
        }
        if (cameraPermissionDenied) {
            ErrorStateRow(headline = stringResource(R.string.network_child_vpn_camera_permission_denied), detail = "")
        }
        OutlinedButton(
            onClick = {
                val hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                if (hasCameraPermission) {
                    cameraPermissionDenied = false
                    launchScan()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.network_child_vpn_scan_qr_action)) }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.network_child_vpn_config_label)) },
            singleLine = false,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    onApply(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) { Text(stringResource(R.string.network_child_vpn_apply_action)) }
            if (configuredEndpoint != null) {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.network_child_vpn_remove_action)) }
            }
        }
    }
}
