package de.ble1st.warden.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import de.ble1st.warden.R
import de.ble1st.warden.appmanagement.SuspiciousAppFindingInfo
import de.ble1st.warden.domain.appmanagement.SuspiciousSignal
import de.ble1st.warden.domain.appmanagement.ThreatSeverity
import de.ble1st.warden.domain.encryption.EncryptionRecommendationDecision
import de.ble1st.warden.domain.encryption.EncryptionRecommendationType
import de.ble1st.warden.domain.advancedprotection.AdvancedProtectionState
import de.ble1st.warden.domain.attestation.AttestationDecision
import de.ble1st.warden.domain.attestation.DeviceAttestation
import de.ble1st.warden.domain.attestation.VerifiedBootState
import de.ble1st.warden.domain.encryption.KeystoreSecurityLevel
import de.ble1st.warden.domain.score.SecurityScoreBreakdown
import de.ble1st.warden.integrity.DeviceIntegrityStatus
import de.ble1st.warden.integrity.RootIndicatorSignal
import de.ble1st.warden.score.SecurityScoreHistoryStore
import de.ble1st.warden.ui.theme.mono

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
 *
 * **Trägt seit 2026-09-05 zusätzlich den Security-Score-Abschnitt** (vorher ein eigener
 * `WardenScreen.SecurityScore`-Bildschirm mit eigenem Dashboard-Menüpunkt, Nutzerwunsch
 * "verschiebe ihn in den Security-Teil, kein eigenes Untermenü") — [SecurityScoreSection] bleibt
 * ein eigenständiger, in sich geschlossener Composable (eigene Datei, eigener "Berechnen"-Button,
 * eigene Fehlerbehandlung), nur ohne eigenes `Scaffold`. Dieselbe Begründung wie zuvor gilt
 * unverändert: die vier zugrunde liegenden Lesepfade sind zusammen zu teuer für automatisches
 * Mitlaufen beim Öffnen dieses Bildschirms, deshalb weiterhin ein expliziter Button statt einer
 * mitgeladenen Kennzahl.
 *
 * **Ganzer Bildschirm auf eine einzige `LazyColumn` umgestellt, gleicher Anlass.** Vorher stand
 * die Funde-`LazyColumn` als letztes Kind in einer nicht scrollbaren `Column` — bei wenig Inhalt
 * unauffällig, aber verwandt mit der in `PerformanceMonitorScreen` dokumentierten Falle ("eine
 * `LazyColumn` verschachtelt in einer `verticalScroll`-`Column` stürzt ab, sobald echte Daten da
 * sind", s. `warden/CLAUDE.md` Abschnitt "Threat severity, permission audit, performance monitor")
 * — hier zwar ohne `verticalScroll`, also kein harter Absturz, aber derselbe Kern-Fehler: alles vor
 * der `LazyColumn` ist nicht scrollbar. Mit dem neuen
 * Security-Score-Abschnitt (Gauge, vier Kategorie-Zeilen samt Begründungstexten, bis zu 30 Tage
 * Historie) wurde dieser Kopfbereich groß genug, dass er auf kleinen Bildschirmen echten Inhalt
 * abschneiden konnte. Jetzt ist der gesamte Kopfbereich (Scanner-Schalter, Intro, Geräte-
 * Integrität, Security-Score) selbst der erste Satz `item { }`-Einträge derselben `LazyColumn`,
 * die Funde-Zeilen bleiben `items(...)` direkt danach — ein einziger scrollbarer Bereich statt
 * zwei getrennter.
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
    /** Vorschlag V-6 (2026-08-29): lädt Schalterzustand, Funde und Integritätsstatus neu, ohne
     * den Bildschirm zu verlassen. Anders als [onRunImmediateScan] wird dabei **kein** neuer Scan
     * ausgelöst — nur das Lesen wiederholt, das fehlgeschlagen ist. */
    onRetry: () -> Unit,
    onToggleScannerEnabled: (Boolean) -> Unit,
    onTrust: (packageName: String) -> Unit,
    onRunImmediateScan: () -> Unit,
    securityScoreBreakdown: SecurityScoreBreakdown?,
    securityScoreCalculationFailed: Boolean,
    securityScoreCalculationInProgress: Boolean,
    securityScoreHistory: List<SecurityScoreHistoryStore.HistoryEntry>,
    onCalculateSecurityScore: () -> Unit,
    /** Tier-2-B5 (2026-09-05): schaltet die Entwickleroptionen/ADB per bestehendem Safeguard
     * `debugging_features_disabled` ab. Der Bildschirm hat diese beiden Zeilen bisher nur
     * *gemeldet* — der Nutzer musste den passenden Schalter im Safeguards-Bildschirm selbst
     * finden. Ein Befund, der direkt neben sich seine Abhilfe trägt, ist der eigentliche
     * Unterschied zwischen einem Melde- und einem Verwaltungswerkzeug (TestDPC-Vergleich). */
    onDisableDebuggingFeatures: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.security_scanner_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_description_back),
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
        // Vorschlag V-3 (2026-08-29): kritische Funde zuerst. Vorher stand die Liste in
        // Scan-Reihenfolge, also praktisch in Paketreihenfolge — ein kritischer Signaturwechsel
        // konnte unter zehn Info-Funden ("unbekannte Installationsquelle") liegen und war nur
        // durch Scrollen zu finden. Innerhalb einer Stufe nach Namen, damit die Reihenfolge
        // zwischen zwei Scans stabil bleibt und nicht bei jedem Neuladen springt.
        // Hier oben berechnet, nicht in der `LazyColumn`-DSL selbst: deren Rumpf
        // (`LazyListScope.() -> Unit`) ist keine `@Composable`-Funktion — nur `item{}`/`items{}`
        // sind es —, ein direkter `remember(...)`-Aufruf dort schlägt beim Kompilieren fehl.
        val sorted = remember(findings) {
            findings.sortedWith(
                compareByDescending<SuspiciousAppFindingInfo> { it.severity.ordinal }
                    .thenBy { it.label.lowercase() },
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val autofreezeLabel = stringResource(R.string.security_scanner_autofreeze_label)
                val autofreezeStateOn = stringResource(R.string.security_scanner_autofreeze_state_on)
                val autofreezeStateOff = stringResource(R.string.security_scanner_autofreeze_state_off)
                val autofreezeStateUnknown = stringResource(R.string.security_scanner_autofreeze_state_unknown)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = autofreezeLabel
                                stateDescription = when (scannerEnabled) {
                                    true -> autofreezeStateOn
                                    false -> autofreezeStateOff
                                    null -> autofreezeStateUnknown
                                }
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = autofreezeLabel, style = MaterialTheme.typography.titleMedium)
                        Switch(
                            checked = scannerEnabled == true,
                            enabled = scannerEnabled != null,
                            onCheckedChange = onToggleScannerEnabled,
                        )
                    }
                    if (scannerEnabled == null) {
                        ErrorStateRow(
                            headline = stringResource(R.string.security_scanner_status_unreadable_headline),
                            detail = stringResource(R.string.security_scanner_status_unreadable_detail),
                            onRetry = onRetry,
                        )
                    }
                    Text(
                        text = stringResource(R.string.security_scanner_intro),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onRunImmediateScan, enabled = !scanInProgress) {
                            Text(stringResource(R.string.security_scanner_run_now_action))
                        }
                        // Punkt 1 ("weitere App-UI-Verschönerungen", 2026-08-22) — das brandneue,
                        // wellenförmige Material-3-Expressive `LoadingIndicator` steckt noch in
                        // material3 1.5.0-alpha, die vom Projekt gepinnte stabile BOM-Version
                        // liefert nur bis 1.4.0; ein Alpha-Artefakt für eine reine Kosmetik-
                        // Ergänzung zu ziehen wäre unverhältnismäßig. `CircularProgressIndicator`
                        // (stabil, seit Jahren Teil von Material 3) erfüllt denselben Zweck:
                        // sichtbares Feedback während des Scans.
                        if (scanInProgress) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                    HorizontalDivider()
                    Text(text = stringResource(R.string.security_scanner_integrity_title), style = MaterialTheme.typography.titleMedium)
                    DeviceIntegritySection(
                        deviceIntegrityStatus,
                        onRetry = onRetry,
                        onDisableDebuggingFeatures = onDisableDebuggingFeatures,
                    )
                    HorizontalDivider()
                    Text(text = stringResource(R.string.security_score_title), style = MaterialTheme.typography.titleMedium)
                    SecurityScoreSection(
                        breakdown = securityScoreBreakdown,
                        calculationFailed = securityScoreCalculationFailed,
                        calculationInProgress = securityScoreCalculationInProgress,
                        history = securityScoreHistory,
                        onCalculate = onCalculateSecurityScore,
                    )
                    HorizontalDivider()
                    Text(text = stringResource(R.string.security_scanner_findings_title), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (findingsLoadFailed) {
                // Dieselbe Unterscheidung wie in AppManagementScreen (Klassendoc dort): ein
                // Scan-Fehler (typischerweise fehlender Device Owner, s. AppFreezeManager) darf
                // nicht wie "nichts Verdächtiges gefunden" aussehen.
                item {
                    ErrorStateRow(
                        headline = stringResource(R.string.security_scanner_findings_unreadable_headline),
                        detail = stringResource(R.string.security_scanner_no_device_owner_detail),
                        onRetry = onRetry,
                    )
                }
            } else if (findings.isEmpty()) {
                item { EmptyStateRow(headline = stringResource(R.string.security_scanner_findings_empty_headline)) }
            } else {
                items(sorted, key = { it.packageName }) { finding ->
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

/** `null` = Laden fehlgeschlagen — dieselbe Fail-Safe-Unterscheidung wie [findingsLoadFailed]
 * oben: ein Lesefehler darf nicht wie "alles unauffällig" aussehen. */
@Composable
private fun DeviceIntegritySection(
    status: DeviceIntegrityStatus?,
    onRetry: () -> Unit,
    onDisableDebuggingFeatures: () -> Unit,
) {
    if (status == null) {
        ErrorStateRow(
            headline = stringResource(R.string.security_scanner_integrity_unreadable_headline),
            detail = stringResource(R.string.security_scanner_no_device_owner_detail),
            onRetry = onRetry,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Tier-2 der DPC-Recherche (2026-09-05): Diese beiden Zeilen meldeten den Befund bisher
        // nur — obwohl Warden ihn als Device Owner selbst beheben kann. Der Katalog-Safeguard
        // `debuggingFeaturesDisabled` (DISALLOW_DEBUGGING_FEATURES) deckt beide ab. Sie sind jetzt
        // antippbar, aber **nur wenn sie tatsächlich aktiv sind**: eine Zeile, die schon "inaktiv"
        // sagt, hätte keine sinnvolle Aktion und dürfte nicht wie ein Schalter aussehen.
        IntegrityStatusRow(
            label = stringResource(R.string.security_scanner_adb_label),
            active = status.adbEnabled,
            onFix = onDisableDebuggingFeatures.takeIf { status.adbEnabled },
        )
        IntegrityStatusRow(
            label = stringResource(R.string.security_scanner_developer_options_label),
            active = status.developerOptionsEnabled,
            onFix = onDisableDebuggingFeatures.takeIf { status.developerOptionsEnabled },
        )
        // Eigene Ergänzung (2026-08-22): anders als die beiden Zeilen oben ist "aktiv" hier GUT,
        // nicht schlecht — eigene Zeile statt IntegrityStatusRow-Wiederverwendung mit invertierter
        // Farblogik, sonst müsste jede/r Leser*in die Bedeutung von "aktiv" pro Zeile neu prüfen.
        EncryptionStatusRow(encrypted = status.storageEncrypted)
        // Feature 5 "Storage Encryption Verification" (nachgeholt 2026-09-04): dieselbe
        // "aktiv = gut"-Farblogik wie EncryptionStatusRow direkt darüber.
        KeystoreStatusRow(level = status.keystoreSecurityLevel)
        // Tier-1 der DPC-Recherche (2026-09-05): Key Attestation und Androids „Erweiterter
        // Schutz". Beide stehen bewusst direkt vor der Root-Heuristik — sie beantworten dieselbe
        // Frage (Geräteintegrität) mit deutlich höherer Beweiskraft, und die Reihenfolge
        // stark → schwach macht sichtbar, welcher Zeile man mehr glauben darf.
        AttestationRows(attestation = status.attestation)
        AdvancedProtectionRow(state = status.advancedProtection)
        if (status.rootIndicators.isEmpty()) {
            EmptyStateRow(headline = stringResource(R.string.security_scanner_root_indicators_empty))
        } else {
            Text(
                text = String.format(
                    stringResource(R.string.security_scanner_root_indicators_prefix),
                    status.rootIndicators.joinToString(", ") { rootIndicatorText(it) },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        HorizontalDivider()
        Text(text = stringResource(R.string.security_scanner_recommendations_title), style = MaterialTheme.typography.titleMedium)
        EncryptionRecommendationsSection(
            storageEncrypted = status.storageEncrypted,
            keystoreSecurityLevel = status.keystoreSecurityLevel,
        )
    }
}

/** Reine Ableitung aus bereits geladenen Werten — kein eigener ConcordBus-Aufruf nötig
 * ([EncryptionRecommendationDecision] ist zustandslos, s. dessen Klassendoc). */
@Composable
private fun EncryptionRecommendationsSection(
    storageEncrypted: Boolean,
    keystoreSecurityLevel: KeystoreSecurityLevel,
) {
    val recommendations = remember(storageEncrypted, keystoreSecurityLevel) {
        EncryptionRecommendationDecision.evaluate(storageEncrypted, keystoreSecurityLevel)
    }
    if (recommendations.isEmpty()) {
        EmptyStateRow(headline = stringResource(R.string.security_scanner_recommendations_empty))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        recommendations.forEach { recommendation ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SeverityBadge(recommendation.severity)
                Text(
                    text = encryptionRecommendationText(recommendation.type),
                    style = MaterialTheme.typography.bodySmall,
                    color = severityColor(recommendation.severity),
                )
            }
        }
    }
}

@Composable
private fun encryptionRecommendationText(type: EncryptionRecommendationType): String =
    when (type) {
        EncryptionRecommendationType.DEVICE_ENCRYPTION_INACTIVE ->
            stringResource(R.string.security_scanner_recommendation_device_encryption_inactive)
        EncryptionRecommendationType.KEYSTORE_SOFTWARE_ONLY ->
            stringResource(R.string.security_scanner_recommendation_keystore_software_only)
        EncryptionRecommendationType.KEYSTORE_UNKNOWN ->
            stringResource(R.string.security_scanner_recommendation_keystore_unknown)
    }

/** Dieselbe "aktiv/gut vs. schlecht"-Farblogik wie [EncryptionStatusRow] direkt darüber —
 * `UNKNOWN` bekommt bewusst die neutrale Farbe, keine der beiden Bewertungen (dieselbe
 * Nicht-Bestrafung von Unsicherheit wie [EncryptionRecommendationDecision]/`SecurityScoreDecision`). */
@Composable
private fun KeystoreStatusRow(level: KeystoreSecurityLevel) {
    val label = stringResource(R.string.security_scanner_keystore_label)
    val stateText = when (level) {
        KeystoreSecurityLevel.HARDWARE_BACKED -> stringResource(R.string.security_scanner_keystore_state_hardware)
        KeystoreSecurityLevel.SOFTWARE -> stringResource(R.string.security_scanner_keystore_state_software)
        KeystoreSecurityLevel.UNKNOWN -> stringResource(R.string.security_scanner_keystore_state_unknown)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                stateDescription = stateText
            },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Text(
            text = stateText,
            style = MaterialTheme.typography.bodySmall,
            color = if (level == KeystoreSecurityLevel.SOFTWARE) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Key-Attestation-Zeilen (2026-09-05). Drei Werte statt einem, weil sie unterschiedlich stark
 * sind und getrennt gelesen werden müssen: Verified-Boot-Zustand, Bootloader-Sperre und
 * Patch-Stand. Ist gar nichts auslesbar ([VerifiedBootState.UNBEKANNT] ohne weitere Werte), wird
 * **eine** erklärende Zeile gezeigt statt dreimal "unbekannt" — auf vielen OEM-Geräten ist das der
 * Normalfall und soll nicht wie ein dreifacher Mangel aussehen.
 */
@Composable
private fun AttestationRows(attestation: DeviceAttestation) {
    val unavailable = attestation.verifiedBootState == VerifiedBootState.UNBEKANNT &&
        attestation.deviceLocked == null &&
        attestation.osPatchLevel == null
    if (unavailable) {
        Text(
            text = stringResource(R.string.security_scanner_attestation_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val bootIsGood = attestation.verifiedBootState == VerifiedBootState.VERIFIED
    val bootIsNeutral = attestation.verifiedBootState == VerifiedBootState.SELF_SIGNED ||
        attestation.verifiedBootState == VerifiedBootState.UNBEKANNT
    AttestationValueRow(
        label = stringResource(R.string.security_scanner_attestation_boot_label),
        value = attestation.verifiedBootState.label,
        isProblem = !bootIsGood && !bootIsNeutral,
    )
    attestation.deviceLocked?.let { locked ->
        AttestationValueRow(
            label = stringResource(R.string.security_scanner_attestation_locked_label),
            value = if (locked) {
                stringResource(R.string.security_scanner_attestation_locked_yes)
            } else {
                stringResource(R.string.security_scanner_attestation_locked_no)
            },
            isProblem = !locked,
        )
    }
    attestation.osPatchLevel?.let { patch ->
        val months = AttestationDecision.monthsBetween(patch, currentYearMonthForUi())
        AttestationValueRow(
            label = stringResource(R.string.security_scanner_attestation_patch_label),
            value = formatPatchLevel(patch),
            isProblem = months != null && months >= AttestationDecision.PATCH_LEVEL_WARN_MONTHS,
        )
    }
    // Der Hinweis auf die Grenzen ist Absicht, gleiche Haltung wie bei CellSecurityField: das hier
    // ist stark, aber nicht unfehlbar (Keybox-Leaks, fehlerhafte OEM-Implementierungen).
    if (attestation.chainTrusted == false) {
        Text(
            text = stringResource(R.string.security_scanner_attestation_chain_untrusted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** `YYYYMM` → `MM/YYYY`, ohne `SimpleDateFormat`-Umweg: der Wert ist bereits ein reiner
 * Zahlencode, kein Zeitstempel. */
private fun formatPatchLevel(yearMonth: Int): String {
    val year = yearMonth / 100
    val month = yearMonth % 100
    return "%02d/%04d".format(month, year)
}

private fun currentYearMonthForUi(): Int {
    val now = java.time.YearMonth.now()
    return now.year * 100 + now.monthValue
}

@Composable
private fun AttestationValueRow(label: String, value: String, isProblem: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                stateDescription = value
            },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** [AdvancedProtectionState.NICHT_VERFUEGBAR] wird bewusst in der neutralen Farbe gezeigt, nicht
 * in der Fehlerfarbe: auf Android 15 (dem aktuellen Zielgerätestand) fehlt die Funktion der
 * Plattform, das ist kein Mangel des Geräts und schon gar keiner, den der Nutzer beheben könnte. */
@Composable
private fun AdvancedProtectionRow(state: AdvancedProtectionState) {
    AttestationValueRow(
        label = stringResource(R.string.security_scanner_advanced_protection_label),
        value = state.label,
        isProblem = state == AdvancedProtectionState.AUS,
    )
}

/** [onFix] `null` = keine Aktion anbieten (Normalfall: nichts zu beheben). Ist eine Aktion
 * hinterlegt, wird die ganze Zeile antippbar und trägt einen sichtbaren Hinweis — ein reiner
 * `clickable`-Modifier ohne optische Änderung wäre eine versteckte Funktion. */
@Composable
private fun IntegrityStatusRow(label: String, active: Boolean, onFix: (() -> Unit)? = null) {
    val activeState = stringResource(R.string.security_scanner_state_active)
    val inactiveState = stringResource(R.string.security_scanner_state_inactive)
    val fixLabel = stringResource(R.string.security_scanner_fix_action)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onFix != null) Modifier.clickable { onFix() } else Modifier)
            .semantics {
                contentDescription = if (onFix != null) "$label — $fixLabel" else label
                stateDescription = if (active) activeState else inactiveState
            },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Text(
            text = if (onFix != null) {
                "${if (active) activeState else inactiveState} · $fixLabel"
            } else {
                if (active) activeState else inactiveState
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EncryptionStatusRow(encrypted: Boolean) {
    val label = stringResource(R.string.security_scanner_encryption_label)
    val activeState = stringResource(R.string.security_scanner_state_active)
    val inactiveState = stringResource(R.string.security_scanner_state_inactive)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                stateDescription = if (encrypted) activeState else inactiveState
            },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Text(
            text = if (encrypted) activeState else inactiveState,
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
    val contentDescriptionTemplate = stringResource(R.string.security_scanner_finding_content_description)
    val frozenState = stringResource(R.string.security_scanner_finding_frozen_state)
    val notFrozenState = stringResource(R.string.security_scanner_finding_not_frozen_state)
    val frozenSuffix = stringResource(R.string.security_scanner_finding_frozen_suffix)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // Vorschlag V-5 (2026-08-29) — dieselbe Korrektur wie in `AppManagementRowContent`,
            // Begründung dort. Der Schweregrad kommt mit in die Beschreibung: er stand bisher nur
            // als farbiges Badge da und war für eine Vorlesehilfe damit gar nicht vorhanden.
            .semantics(mergeDescendants = true) {
                contentDescription = String.format(
                    contentDescriptionTemplate,
                    finding.label,
                    finding.packageName,
                    severityLabel(finding.severity),
                )
                stateDescription = if (finding.frozen) frozenState else notFrozenState
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = finding.label, style = MaterialTheme.typography.bodyLarge)
                SeverityBadge(finding.severity)
            }
            Text(text = finding.packageName, style = MaterialTheme.typography.bodySmall.mono())
            Text(
                text = signalsText(SuspiciousSignal.fromBitmask(finding.signalsBitmask)) +
                    if (finding.frozen) frozenSuffix else "",
                style = MaterialTheme.typography.bodySmall,
                color = severityColor(finding.severity),
            )
        }
        TextButton(onClick = onTrust) {
            Text(stringResource(R.string.security_scanner_trust_action))
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

/** Nicht `private`: seit Vorschlag V-2 (2026-08-29) zeigt auch die Dashboard-Menüzeile den
 * höchsten Schweregrad an, und zwei getrennte Übersetzungstabellen für dieselben drei Stufen
 * würden früher oder später auseinanderlaufen. */
internal fun severityLabel(severity: ThreatSeverity): String = when (severity) {
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
        // Vorschlag V-5 (2026-08-29): keine eigene contentDescription mehr. Die Zeile ist jetzt
        // ein zusammengeführter Semantikknoten, der den Schweregrad selbst nennt — eine zweite
        // hier würde in dieselbe Beschreibung einfließen und doppelt vorgelesen.
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
            SuspiciousSignal.PERMISSION_ESCALATED -> "Neue gefährliche Rechte seit Update"
        }
    }
