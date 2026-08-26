package de.ble1st.warden.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import de.ble1st.warden.appmanagement.SuspiciousAppFindingInfo
import de.ble1st.warden.domain.appmanagement.SuspiciousSignal
import de.ble1st.warden.domain.appmanagement.ThreatSeverity
import de.ble1st.warden.integrity.DeviceIntegrityStatus
import de.ble1st.warden.integrity.RootIndicatorSignal

/**
 * Milestone "Automatisches Einfrieren verdächtiger Apps" — portiert aus Heralds UI (jetzt Wardens
 * eigener In-App-Bildschirm, s. Plan-Abschnitt "Herald-UI einbauen"). Zwei Teile: ein Ein/Aus-
 * Schalter für den Scanner selbst (Default aus, s.
 * [de.ble1st.warden.appmanagement.SuspiciousAppScanController]-Klassendoc) und die aktuelle
 * Funde-Liste (läuft unabhängig vom Schalter, reine Transparenz) mit einem "Vertrauen"-Button pro
 * Zeile — hebt ein bereits erfolgtes automatisches Einfrieren sofort auf und verhindert ein
 * erneutes.
 *
 * Seit "weitere Funktionen für den Sicherheitsscanner" (2026-08-22) zwei weitere Bausteine: ein
 * "Jetzt scannen"-Button (Feature 10, [onRunImmediateScan]) und ein "Geräte-Integrität"-Abschnitt
 * ([deviceIntegrityStatus], Features 8/9 — Root-/Magisk-Indikatoren, ADB-/Entwickleroptionen-
 * Status). Beides geräteweite Zustände statt App-bezogener Funde, deshalb eigene Sektionen statt
 * Teil der Funde-Liste.
 *
 * Nimmt [SuspiciousAppFindingInfo] direkt entgegen (kein Binder-Overhead mehr, s.
 * [AppManagementScreen]-Klassendoc für dieselbe Begründung).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScannerScreen(
    scannerEnabled: Boolean?,
    findings: List<SuspiciousAppFindingInfo>,
    findingsLoadFailed: Boolean,
    deviceIntegrityStatus: DeviceIntegrityStatus?,
    scanInProgress: Boolean,
    onBack: () -> Unit,
    onToggleScannerEnabled: (Boolean) -> Unit,
    onTrust: (packageName: String) -> Unit,
    onRunImmediateScan: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sicherheits-Scanner") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Zurück",
                        )
                    }
                },
            )
        },
    ) { padding ->
        // Punkt 8 ("weitere App-UI-Verschönerungen", 2026-08-22) — zusätzlich zum expliziten
        // "Jetzt scannen"-Button: Herunterziehen löst denselben Sofort-Scan aus. Der Button bleibt
        // bestehen (bewusste, unmissverständliche Aktion), Pull-to-Refresh ist nur eine zweite,
        // gängige Geste für dasselbe Ergebnis, kein Ersatz.
        PullToRefreshBox(
            isRefreshing = scanInProgress,
            onRefresh = onRunImmediateScan,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Automatisch einfrieren"
                        stateDescription = when (scannerEnabled) {
                            true -> "an"
                            false -> "aus"
                            null -> "unbekannt"
                        }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Automatisch einfrieren", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = scannerEnabled == true,
                    enabled = scannerEnabled != null,
                    onCheckedChange = onToggleScannerEnabled,
                )
            }
            if (scannerEnabled == null) {
                ErrorStateRow(
                    headline = "Scanner-Status konnte nicht gelesen werden",
                    detail = "Schalter deaktiviert, bis der Zustand wieder lesbar ist.",
                )
            }
            Text(
                text = "Scannt installierte Apps auf im Manifest deklarierte Geräteadministrator-" +
                    "Rechte oder Bedienungshilfen-Dienste — bekannte Maschen bösartiger Apps, " +
                    "erkannt bevor die Rechte überhaupt aktiviert wurden. Systemapps sind " +
                    "ausgenommen. Bei jedem Fund erscheint sofort eine Sicherheitsbenachrichtigung " +
                    "mit den Optionen \"Einfrieren\"/\"Deinstallieren\" — unabhängig vom Schalter " +
                    "unten, der zusätzlich stilles Auto-Einfrieren aktiviert. Jederzeit reversibel " +
                    "über \"Vertrauen\" unten oder die App-Verwaltung.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRunImmediateScan, enabled = !scanInProgress) {
                    Text("Jetzt scannen")
                }
                // Punkt 1 ("weitere App-UI-Verschönerungen", 2026-08-22) — das brandneue, wellen-
                // förmige Material-3-Expressive `LoadingIndicator` steckt noch in material3
                // 1.5.0-alpha, die vom Projekt gepinnte stabile BOM-Version liefert nur bis 1.4.0;
                // ein Alpha-Artefakt für eine reine Kosmetik-Ergänzung zu ziehen wäre unverhältnis-
                // mäßig. `CircularProgressIndicator` (stabil, seit Jahren Teil von Material 3)
                // erfüllt denselben Zweck: sichtbares Feedback während des Scans.
                if (scanInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            HorizontalDivider()
            Text(text = "Geräte-Integrität", style = MaterialTheme.typography.titleMedium)
            DeviceIntegritySection(deviceIntegrityStatus)
            HorizontalDivider()
            Text(text = "Aktuelle Funde", style = MaterialTheme.typography.titleMedium)
            if (findingsLoadFailed) {
                // Dieselbe Unterscheidung wie in AppManagementScreen (Klassendoc dort): ein
                // Scan-Fehler (typischerweise fehlender Device Owner, s. AppFreezeManager) darf
                // nicht wie "nichts Verdächtiges gefunden" aussehen.
                ErrorStateRow(
                    headline = "Funde-Liste konnte nicht geladen werden",
                    detail = "Vermutlich kein Device Owner aktiv — s. Statusanzeige.",
                )
            } else if (findings.isEmpty()) {
                EmptyStateRow(headline = "Keine verdächtigen Apps gefunden")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(findings, key = { it.packageName }) { finding ->
                        FindingRow(
                            finding = finding,
                            onTrust = { onTrust(finding.packageName) },
                        )
                    }
                }
            }
        }
        }
    }
}

/** `null` = Laden fehlgeschlagen — dieselbe Fail-Safe-Unterscheidung wie [findingsLoadFailed]
 * oben: ein Lesefehler darf nicht wie "alles unauffällig" aussehen. */
@Composable
private fun DeviceIntegritySection(status: DeviceIntegrityStatus?) {
    if (status == null) {
        ErrorStateRow(
            headline = "Geräte-Integritätsstatus konnte nicht geladen werden",
            detail = "Vermutlich kein Device Owner aktiv — s. Statusanzeige.",
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        IntegrityStatusRow(label = "ADB-Debugging", active = status.adbEnabled)
        IntegrityStatusRow(label = "Entwickleroptionen", active = status.developerOptionsEnabled)
        // Eigene Ergänzung (2026-08-22): anders als die beiden Zeilen oben ist "aktiv" hier GUT,
        // nicht schlecht — eigene Zeile statt IntegrityStatusRow-Wiederverwendung mit invertierter
        // Farblogik, sonst müsste jede/r Leser*in die Bedeutung von "aktiv" pro Zeile neu prüfen.
        EncryptionStatusRow(encrypted = status.storageEncrypted)
        if (status.rootIndicators.isEmpty()) {
            EmptyStateRow(headline = "Keine Root-/Magisk-/Custom-ROM-Indikatoren gefunden")
        } else {
            Text(
                text = "Root-Indikatoren: " + status.rootIndicators.joinToString(", ") { rootIndicatorText(it) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun IntegrityStatusRow(label: String, active: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                stateDescription = if (active) "aktiv" else "inaktiv"
            },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Text(
            text = if (active) "aktiv" else "inaktiv",
            style = MaterialTheme.typography.bodySmall,
            color = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EncryptionStatusRow(encrypted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Speicherverschlüsselung"
                stateDescription = if (encrypted) "aktiv" else "inaktiv"
            },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = "Speicherverschlüsselung", style = MaterialTheme.typography.bodySmall)
        Text(
            text = if (encrypted) "aktiv" else "inaktiv",
            style = MaterialTheme.typography.bodySmall,
            color = if (encrypted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
    }
}

private fun rootIndicatorText(signal: RootIndicatorSignal): String = when (signal) {
    RootIndicatorSignal.SU_BINARY_FOUND -> "su-Binary gefunden"
    RootIndicatorSignal.MAGISK_PACKAGE_FOUND -> "Magisk-Paket installiert"
    RootIndicatorSignal.TEST_KEYS_BUILD -> "test-keys-Build"
}

@Composable
private fun FindingRow(
    finding: SuspiciousAppFindingInfo,
    onTrust: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = "Verdachtsfund ${finding.label}"
                stateDescription = if (finding.frozen) "eingefroren" else "nicht eingefroren"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = finding.label, style = MaterialTheme.typography.bodyLarge)
                SeverityBadge(finding.severity)
            }
            Text(text = finding.packageName, style = MaterialTheme.typography.bodySmall)
            Text(
                text = signalsText(SuspiciousSignal.fromBitmask(finding.signalsBitmask)) +
                    if (finding.frozen) " · eingefroren" else "",
                style = MaterialTheme.typography.bodySmall,
                color = severityColor(finding.severity),
            )
        }
        TextButton(onClick = onTrust) {
            Text("Vertrauen")
        }
    }
}

/** "Threat Alerts & Severity Levels" (2026-08-25) — Ampelfarbe je [ThreatSeverity], geteilt
 * zwischen dieser Zeile (Signaltext-Farbe, Badge-Hintergrund) und
 * [de.ble1st.warden.appmanagement.SuspiciousAppNotifier]s Kanalfarbe (dort eigene Kopie, da
 * `android.graphics.Color` statt `androidx.compose.ui.graphics.Color` — kein gemeinsamer Typ ohne
 * Compose-Abhängigkeit im `appmanagement`-Package einzuführen, s. dortiges Klassendoc). */
private fun severityColor(severity: ThreatSeverity): Color = when (severity) {
    ThreatSeverity.INFO -> Color(0xFF1565C0)
    ThreatSeverity.WARNING -> Color(0xFFE65100)
    ThreatSeverity.CRITICAL -> Color(0xFFB00020)
}

private fun severityLabel(severity: ThreatSeverity): String = when (severity) {
    ThreatSeverity.INFO -> "Info"
    ThreatSeverity.WARNING -> "Warnung"
    ThreatSeverity.CRITICAL -> "Kritisch"
}

@Composable
private fun SeverityBadge(severity: ThreatSeverity) {
    Text(
        text = severityLabel(severity),
        style = MaterialTheme.typography.labelSmall,
        color = severityColor(severity),
        modifier = Modifier.semantics { contentDescription = "Stufe ${severityLabel(severity)}" },
    )
}

private fun signalsText(signals: Set<SuspiciousSignal>): String =
    signals.joinToString(", ") { signal ->
        when (signal) {
            SuspiciousSignal.EXTRA_DEVICE_ADMIN -> "Geräteadministrator"
            SuspiciousSignal.ACCESSIBILITY_SERVICE_DECLARED -> "Bedienungshilfen-Dienst im Manifest"
            SuspiciousSignal.OVERLAY_PERMISSION_DECLARED -> "Overlay-Rechte"
            SuspiciousSignal.NOTIFICATION_LISTENER_DECLARED -> "Benachrichtigungs-Zugriff"
            SuspiciousSignal.UNKNOWN_INSTALL_SOURCE -> "unbekannte Installationsquelle"
            SuspiciousSignal.SIGNING_CERT_CHANGED -> "Signatur geändert"
            SuspiciousSignal.DEVICE_ADMIN_NEWLY_ACTIVATED -> "Geräteadmin gerade aktiviert"
            SuspiciousSignal.ACCESSIBILITY_SERVICE_NEWLY_ACTIVATED -> "Bedienungshilfe gerade aktiviert"
            SuspiciousSignal.VERSION_DOWNGRADED -> "Version zurückgestuft"
        }
    }
