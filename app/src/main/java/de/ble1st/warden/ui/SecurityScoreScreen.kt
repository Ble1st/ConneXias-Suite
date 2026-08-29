package de.ble1st.warden.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.ble1st.warden.domain.score.SecurityScoreBreakdown
import de.ble1st.warden.domain.score.SecurityScoreLevel

/**
 * Feature 5 "Security Score Dashboard" aus `docs/umsetzungsplan-7-features.md`, 2026-08-29 — s.
 * [de.ble1st.warden.domain.score.SecurityScoreDecision]-Klassendoc für die Abweichungen vom Plan
 * (vier statt fünf Kategorien, keine 30-Tage-Historie, kein kreisförmiger Gauge).
 *
 * Eigenständiger Bildschirm statt einer weiteren Kennzahl direkt auf dem Dashboard — die vier
 * zugrunde liegenden Lesepfade ([de.ble1st.warden.score.SecurityScoreCalculator]-Klassendoc) sind
 * zusammen zu teuer, um bei jedem Öffnen des Dashboards automatisch mitzulaufen (dieselbe
 * Erwägung wie beim eigenständigen Permission-Audit-Bildschirm), deshalb wie dieser über einen
 * expliziten "Berechnen"-Button statt automatisch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScoreScreen(
    breakdown: SecurityScoreBreakdown?,
    calculationFailed: Boolean,
    calculationInProgress: Boolean,
    onBack: () -> Unit,
    onCalculate: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sicherheits-Score") },
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Fasst Verdachtsscanner, Permission-Audit, Geräte-Integrität und den " +
                    "Härtungsgrad der Safeguards zu einer Kennzahl zusammen. Keine " +
                    "Verlaufsanzeige — jede Berechnung ist eine Momentaufnahme.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCalculate, enabled = !calculationInProgress) {
                    Text(if (breakdown == null) "Berechnen" else "Neu berechnen")
                }
                if (calculationInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            HorizontalDivider()
            when {
                calculationFailed -> EmptyStateRow(
                    headline = "Berechnung fehlgeschlagen",
                    detail = "Vermutlich kein Device Owner aktiv. Erneut versuchen.",
                )
                breakdown == null && !calculationInProgress ->
                    EmptyStateRow(headline = "Noch nicht berechnet", detail = "Auf \"Berechnen\" tippen.")
                breakdown == null -> {}
                else -> {
                    val levelColor = levelColor(breakdown.level)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                            .semantics {
                                contentDescription = "Sicherheits-Score ${breakdown.total} von 100, " +
                                    "Einstufung ${breakdown.level.label}"
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "${breakdown.total}",
                            style = MaterialTheme.typography.displayLarge,
                            color = levelColor,
                        )
                        Text(
                            text = breakdown.level.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = levelColor,
                        )
                    }
                    HorizontalDivider()
                    ScoreCategoryRow("Bedrohungen", breakdown.threatScore)
                    ScoreCategoryRow("Rechte-Hygiene", breakdown.permissionScore)
                    ScoreCategoryRow("Geräte-Integrität", breakdown.integrityScore)
                    ScoreCategoryRow("Härtung", breakdown.hardeningScore)
                }
            }
        }
    }
}

@Composable
private fun levelColor(level: SecurityScoreLevel) = when (level) {
    SecurityScoreLevel.SEHR_GUT, SecurityScoreLevel.GUT -> MaterialTheme.colorScheme.primary
    SecurityScoreLevel.VERBESSERUNGSWUERDIG -> MaterialTheme.colorScheme.tertiary
    SecurityScoreLevel.KRITISCH -> MaterialTheme.colorScheme.error
}

@Composable
private fun ScoreCategoryRow(label: String, score: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label, $score von 100" },
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(text = "$score", style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(
            progress = { score / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}
