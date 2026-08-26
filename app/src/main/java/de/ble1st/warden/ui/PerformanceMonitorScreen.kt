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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.ble1st.warden.performance.AppUsageInfo
import de.ble1st.warden.performance.BatterySnapshot
import de.ble1st.warden.performance.DeviceMemorySnapshot
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
                title = { Text("Performance-Monitor") },
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRefresh) { Text("Aktualisieren") }
            }
            HorizontalDivider()
            Text(text = "Arbeitsspeicher", style = MaterialTheme.typography.titleMedium)
            MemorySection(memory)

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            Text(text = "Akku", style = MaterialTheme.typography.titleMedium)
            BatterySection(battery, batteryDrainPercentPerHour)

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            Text(text = "App-Aktivität (verdächtige Apps hervorgehoben)", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Vordergrund-Nutzungszeit der letzten 24h — kein echter CPU-/RAM-Wert. " +
                    "Android gibt Drittapps (auch Device Owner) keinen Zugriff auf die " +
                    "tatsächliche Prozessorlast/den Speicherverbrauch fremder Apps; " +
                    "Nutzungszeit ist die einzige real verfügbare Aktivitäts-Näherung.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            UsageSection(usageAccessGranted, usageFindings, suspiciousPackageNames, appLabels, onRequestUsageAccess)
        }
    }
}

@Composable
private fun MemorySection(memory: DeviceMemorySnapshot?) {
    if (memory == null) {
        ErrorStateRow(headline = "Speicherstatus nicht lesbar", detail = "ActivityManager nicht verfügbar.")
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
            text = "${formatBytes(memory.totalMemBytes - memory.availMemBytes)} / ${formatBytes(memory.totalMemBytes)} belegt" +
                if (memory.lowMemory) " · ⚠ System meldet Speicherknappheit" else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (memory.lowMemory) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BatterySection(battery: BatterySnapshot?, drainPercentPerHour: Double?) {
    if (battery == null) {
        ErrorStateRow(headline = "Akkustatus nicht lesbar", detail = "Kein ACTION_BATTERY_CHANGED-Broadcast erhalten.")
        return
    }
    Column {
        Text(
            text = "${battery.percent}% — " + if (battery.charging) "lädt" else "entlädt",
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
                battery.charging -> "Drain-Rate: lädt gerade, keine Berechnung."
                drainPercentPerHour == null -> "Drain-Rate: noch nicht genug Messpunkte (Sammlung läuft alle 30 Minuten)."
                else -> "Drain-Rate: ~${formatOneDecimal(drainPercentPerHour)}%/h seit letztem Ladevorgang."
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
) {
    if (!usageAccessGranted) {
        Column {
            Text(
                text = "Nutzungsdatenzugriff nicht erteilt — einmalig manuell freigeben.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRequestUsageAccess) { Text("Einstellungen öffnen") }
        }
        return
    }
    if (usageFindings == null) {
        ErrorStateRow(headline = "Nutzungsdaten nicht lesbar", detail = "UsageStatsManager nicht verfügbar.")
        return
    }
    if (usageFindings.isEmpty()) {
        EmptyStateRow(headline = "Keine Vordergrund-Aktivität in den letzten 24h")
        return
    }
    // Plain Column statt LazyColumn: dieser Screen ist bereits als Ganzes per verticalScroll
    // gescrollt (Memory-/Akku-Abschnitte oben) — eine LazyColumn darin würde mit unbegrenzter
    // Höhe gemessen und crasht hart (IllegalStateException, Compose erlaubt das strukturell
    // nicht). Unproblematisch, weil die Liste ohnehin auf "Fremd-Apps mit Vordergrund-Aktivität
    // in den letzten 24h" begrenzt ist, keine potenziell tausende Einträge lange Liste.
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (info in usageFindings) {
            val suspicious = info.packageName in suspiciousPackageNames
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = (appLabels[info.packageName] ?: info.packageName) + if (suspicious) " ⚠" else "",
                        color = if (suspicious) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(text = info.packageName, style = MaterialTheme.typography.bodySmall)
                }
                Text(text = formatDuration(info.totalForegroundTimeMillis), style = MaterialTheme.typography.bodySmall)
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
