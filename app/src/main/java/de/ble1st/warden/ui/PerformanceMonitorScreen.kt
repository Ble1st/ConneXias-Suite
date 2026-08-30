package de.ble1st.warden.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.ble1st.warden.R
import de.ble1st.warden.performance.AppUsageInfo
import de.ble1st.warden.performance.BatterySnapshot
import de.ble1st.warden.performance.DeviceMemorySnapshot
import de.ble1st.warden.ui.theme.mono
import kotlin.math.roundToInt

/**
 * "Extra Fenster für Performance-Monitoring" (2026-08-25, auf Nutzerwunsch — Use-Case
 * "CPU/Memory-Auslastung durch verdächtige Apps, Battery-Drain-Analyse", Empfehlung "Lokale
 * Statistik-Erfassung via ActivityManager.MemoryInfo, BatteryManager"). Eigener Bildschirm
 * (`WardenScreen.PerformanceMonitor`), kein Tab/Abschnitt eines bestehenden — passt zu keinem der
 * anderen Untermenüs (weder Safeguard-Schalter noch Bedrohungsfunde).
 *
 * **Explizit kein Pro-App-CPU-/RAM-Wert** — `usageFindings`/`usageAccessGranted` liefern
 * Vordergrund-Nutzungszeit, keine echte Ressourcen-Messung, s. [de.ble1st.warden.performance
 * .DeviceMemoryReader]/[de.ble1st.warden.performance.AppUsageReader]-Klassendocs für die
 * Android-Plattformgrenze, die das erzwingt. Der Hinweistext unten macht das für die Nutzerin
 * explizit, statt eine Genauigkeit vorzutäuschen, die die Plattform nicht hergibt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceMonitorScreen(
    memory: DeviceMemorySnapshot?,
    battery: BatterySnapshot?,
    batteryDrainPercentPerHour: Double?,
    usageAccessGranted: Boolean,
    usageFindings: List<AppUsageInfo>?,
    suspiciousPackageNames: Set<String>,
    appLabels: Map<String, String>,
    onBack: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.performance_monitor_title)) },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.performance_monitor_refresh_action)) }
            }
            HorizontalDivider()
            Text(text = stringResource(R.string.performance_monitor_memory_title), style = MaterialTheme.typography.titleMedium)
            MemorySection(memory, onRetry = onRefresh)

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            Text(text = stringResource(R.string.performance_monitor_battery_title), style = MaterialTheme.typography.titleMedium)
            BatterySection(battery, batteryDrainPercentPerHour, onRetry = onRefresh)

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            Text(text = stringResource(R.string.performance_monitor_usage_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.performance_monitor_usage_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            UsageSection(usageAccessGranted, usageFindings, suspiciousPackageNames, appLabels, onRequestUsageAccess, onRefresh)
        }
    }
}

@Composable
private fun MemorySection(memory: DeviceMemorySnapshot?, onRetry: () -> Unit) {
    if (memory == null) {
        // Vorschlag V-12 (2026-08-29): derselbe zweite Versuch wie in V-6. Der
        // "Aktualisieren"-Knopf oben tut dasselbe, steht aber je nach Bildschirmhöhe außer Sicht,
        // sobald man bei einem der unteren Abschnitte angekommen ist.
        ErrorStateRow(headline = stringResource(R.string.performance_monitor_memory_unreadable_headline), detail = stringResource(R.string.performance_monitor_memory_unreadable_detail), onRetry = onRetry)
        return
    }
    val usedFraction = if (memory.totalMemBytes > 0) {
        1f - (memory.availMemBytes.toFloat() / memory.totalMemBytes.toFloat())
    } else {
        0f
    }
    Column {
        LinearProgressIndicator(
            progress = { usedFraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            color = if (memory.lowMemory) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Text(
            text = String.format(stringResource(R.string.performance_monitor_memory_used), formatBytes(memory.totalMemBytes - memory.availMemBytes), formatBytes(memory.totalMemBytes)) +
                if (memory.lowMemory) stringResource(R.string.performance_monitor_memory_low_suffix) else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (memory.lowMemory) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BatterySection(battery: BatterySnapshot?, drainPercentPerHour: Double?, onRetry: () -> Unit) {
    if (battery == null) {
        ErrorStateRow(headline = stringResource(R.string.performance_monitor_battery_unreadable_headline), detail = stringResource(R.string.performance_monitor_battery_unreadable_detail), onRetry = onRetry)
        return
    }
    Column {
        Text(
            text = if (battery.charging) {
                String.format(stringResource(R.string.performance_monitor_battery_percent_charging), battery.percent)
            } else {
                String.format(stringResource(R.string.performance_monitor_battery_percent_discharging), battery.percent)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        val details = buildList {
            battery.temperatureCelsius?.let { add("${it}°C") }
            battery.voltageMillivolts?.let { add("${it} mV") }
        }
        if (details.isNotEmpty()) {
            Text(text = details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = when {
                battery.charging -> stringResource(R.string.performance_monitor_drain_charging)
                drainPercentPerHour == null -> stringResource(R.string.performance_monitor_drain_insufficient_data)
                else -> String.format(stringResource(R.string.performance_monitor_drain_rate), formatOneDecimal(drainPercentPerHour))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UsageSection(
    usageAccessGranted: Boolean,
    usageFindings: List<AppUsageInfo>?,
    suspiciousPackageNames: Set<String>,
    appLabels: Map<String, String>,
    onRequestUsageAccess: () -> Unit,
    onRetry: () -> Unit,
) {
    if (!usageAccessGranted) {
        Column {
            Text(
                text = stringResource(R.string.performance_monitor_usage_access_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRequestUsageAccess) { Text(stringResource(R.string.performance_monitor_open_settings_action)) }
        }
        return
    }
    if (usageFindings == null) {
        ErrorStateRow(headline = stringResource(R.string.performance_monitor_usage_unreadable_headline), detail = stringResource(R.string.performance_monitor_usage_unreadable_detail), onRetry = onRetry)
        return
    }
    if (usageFindings.isEmpty()) {
        EmptyStateRow(headline = stringResource(R.string.performance_monitor_usage_empty_headline))
        return
    }
    // Plain Column statt LazyColumn: dieser Screen ist bereits als Ganzes per verticalScroll
    // gescrollt (Memory-/Akku-Abschnitte oben) — eine LazyColumn darin würde mit unbegrenzter
    // Höhe gemessen und crasht hart (IllegalStateException, Compose erlaubt das strukturell
    // nicht). Unproblematisch, weil die Liste ohnehin auf "Fremd-Apps mit Vordergrund-Aktivität
    // in den letzten 24h" begrenzt ist, keine potenziell tausende Einträge lange Liste.
    // Vorschlag V-11 (2026-08-29): verdächtige Apps zuerst. Die Abschnittsüberschrift verspricht
    // "verdächtige Apps hervorgehoben" — hervorgehoben waren sie bisher nur farblich, standen aber
    // weiter irgendwo in der Nutzungszeit-Reihenfolge. Innerhalb beider Gruppen weiter nach
    // Vordergrundzeit absteigend, also unverändert die bisherige Ordnung.
    val ordered = remember(usageFindings, suspiciousPackageNames) {
        usageFindings.sortedWith(
            compareByDescending<AppUsageInfo> { it.packageName in suspiciousPackageNames }
                .thenByDescending { it.totalForegroundTimeMillis },
        )
    }
    val contentDescriptionTemplate = stringResource(R.string.performance_monitor_row_content_description)
    val suspiciousSuffix = stringResource(R.string.performance_monitor_row_suspicious_suffix)
    val suspiciousMarker = stringResource(R.string.performance_monitor_suspicious_marker)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (info in ordered) {
            val suspicious = info.packageName in suspiciousPackageNames
            val label = appLabels[info.packageName] ?: info.packageName
            val duration = formatDuration(info.totalForegroundTimeMillis)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    // Vorschlag V-11: diese Zeilen hatten bisher gar keine Semantik — App-Name,
                    // Paketname und Nutzungsdauer waren drei getrennte Knoten, die Dauer damit
                    // ohne erkennbaren Bezug zur App darüber. Das "⚠" trug die Verdachtsmarkierung
                    // ausschließlich als Zeichen; je nach Vorlesehilfe wurde es als "Warnzeichen"
                    // oder gar nicht angesagt. Jetzt steht sie als Wort in der Beschreibung.
                    .semantics(mergeDescendants = true) {
                        contentDescription = buildString {
                            append(String.format(contentDescriptionTemplate, label, info.packageName, duration))
                            if (suspicious) append(suspiciousSuffix)
                        }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = label + if (suspicious) suspiciousMarker else "",
                        color = if (suspicious) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(text = info.packageName, style = MaterialTheme.typography.bodySmall.mono())
                }
                Text(text = duration, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
}

private fun formatOneDecimal(value: Double): String = "%.1f".format(value)

private fun formatDuration(millis: Long): String {
    val totalMinutes = (millis / 60_000.0).roundToInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}min" else "${minutes}min"
}
