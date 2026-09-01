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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.ble1st.warden.R
import de.ble1st.warden.domain.score.SecurityScoreBreakdown
import de.ble1st.warden.domain.score.SecurityScoreLevel
import de.ble1st.warden.score.SecurityScoreHistoryStore
import de.ble1st.warden.ui.theme.mono
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Feature 5 "Security Score Dashboard" aus `docs/umsetzungsplan-7-features.md`, 2026-08-29 — s.
 * [de.ble1st.warden.domain.score.SecurityScoreDecision]-Klassendoc für die verbleibenden
 * Abweichungen vom Plan (vier statt fünf Kategorien, kein kreisförmiger Gauge, kein
 * Kategorie-Drill-down). Die 30-Tage-Historie selbst wurde am 2026-08-30 nachgereicht
 * ([SecurityScoreHistoryStore]) — die Berechnungslogik blieb dabei unverändert, nur ein Anbau.
 *
 * Eigenständiger Bildschirm statt einer weiteren Kennzahl direkt auf dem Dashboard — die vier
 * zugrunde liegenden Lesepfade ([de.ble1st.warden.score.SecurityScoreCalculator]-Klassendoc) sind
 * zusammen zu teuer, um bei jedem Öffnen des Dashboards automatisch mitzulaufen (dieselbe
 * Erwägung wie beim eigenständigen Permission-Audit-Bildschirm), deshalb wie dieser über einen
 * expliziten "Berechnen"-Button statt automatisch. Aus demselben Grund füllt sich [history] nur
 * durch echte manuelle Berechnungen, nie über einen periodischen Hintergrund-Worker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScoreScreen(
    breakdown: SecurityScoreBreakdown?,
    calculationFailed: Boolean,
    calculationInProgress: Boolean,
    history: List<SecurityScoreHistoryStore.HistoryEntry>,
    onBack: () -> Unit,
    onCalculate: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.security_score_title)) },
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.security_score_intro),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCalculate, enabled = !calculationInProgress) {
                    Text(if (breakdown == null) stringResource(R.string.security_score_calculate_action) else stringResource(R.string.security_score_recalculate_action))
                }
                if (calculationInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            HorizontalDivider()
            when {
                calculationFailed -> EmptyStateRow(
                    headline = stringResource(R.string.security_score_failed_headline),
                    detail = stringResource(R.string.security_score_failed_detail),
                )
                breakdown == null && !calculationInProgress ->
                    EmptyStateRow(headline = stringResource(R.string.security_score_not_calculated_headline), detail = stringResource(R.string.security_score_not_calculated_detail))
                breakdown == null -> {}
                else -> {
                    val levelColor = levelColor(breakdown.level)
                    val totalContentDescription = String.format(
                        stringResource(R.string.security_score_total_content_description),
                        breakdown.total,
                        breakdown.level.label,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                            .semantics {
                                contentDescription = totalContentDescription
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
                    ScoreCategoryRow(stringResource(R.string.security_score_category_threats), breakdown.threatScore)
                    ScoreCategoryRow(stringResource(R.string.security_score_category_permissions), breakdown.permissionScore)
                    ScoreCategoryRow(stringResource(R.string.security_score_category_integrity), breakdown.integrityScore)
                    ScoreCategoryRow(stringResource(R.string.security_score_category_hardening), breakdown.hardeningScore)
                }
            }
            if (history.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                Text(
                    text = stringResource(R.string.security_score_history_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                // Neueste zuerst — die aktuelle Momentaufnahme steht oben im Bildschirm bereits
                // ausführlich, hier interessiert der Blick zurück.
                for (entry in history.asReversed()) {
                    HistoryRow(entry)
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
    val rowContentDescription = String.format(stringResource(R.string.security_score_category_content_description), label, score)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = rowContentDescription },
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

/** Eine Verlaufszeile — Zeitpunkt, Gesamtscore und Einstufung. Monospace für Zeitpunkt und Score
 * (`TextStyle.mono()`), s. CLAUDE.md "Typography is deliberately mixed": eine Spalte mit
 * Zeitstempeln ist genau der Fall fester Zeichenbreite, den die Konvention als *funktional*
 * einordnet, analog zu den Log-Zeilen in [de.ble1st.warden.presence.LogViewerActivity]. */
@Composable
private fun HistoryRow(entry: SecurityScoreHistoryStore.HistoryEntry) {
    val color = levelColor(entry.level)
    val rowContentDescription = String.format(
        stringResource(R.string.security_score_history_content_description),
        formatHistoryTimestamp(entry.timestampMillis),
        entry.total,
        entry.level.label,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = rowContentDescription
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatHistoryTimestamp(entry.timestampMillis),
            style = MaterialTheme.typography.bodySmall.mono(),
        )
        Text(
            text = String.format(stringResource(R.string.security_score_history_summary), entry.total, entry.level.label),
            style = MaterialTheme.typography.bodySmall.mono(),
            color = color,
        )
    }
}

private val HISTORY_TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

private fun formatHistoryTimestamp(millis: Long): String =
    runCatching { HISTORY_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(millis)) }.getOrDefault("—")
