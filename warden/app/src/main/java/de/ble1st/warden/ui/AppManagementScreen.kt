package de.ble1st.warden.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import de.ble1st.warden.appmanagement.AppManagementInfo
import de.ble1st.warden.ui.theme.mono

/**
 * Milestone "App-Verwaltung: Einfrieren/Deaktivieren" — portiert aus Heralds UI (jetzt Wardens
 * eigener In-App-Bildschirm statt einer separaten APK, s. Plan-Abschnitt "Herald-UI einbauen").
 * Struktur/Stil bewusst wie im Quellprojekt (Suche, geteilter [SystemAppFilterRow]-Filter,
 * `LazyColumn` mit einer Zeile pro App).
 *
 * Anders als im Quellprojekt (dort: `AppManagementUiState`, eine über AIDL-taugliche eigene
 * UI-Datenklasse, befüllt aus einer `suspend`-Bus-Fassade) nimmt dieser Screen
 * [AppManagementInfo] direkt entgegen — [de.ble1st.warden.bus.ConcordBus] ist in-process und
 * synchron, kein Binder-Overhead/keine eigene Parcelable-Zwischenschicht mehr nötig (s.
 * [AppManagementInfo]-Klassendoc).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagementScreen(
    apps: List<AppManagementInfo>,
    loadFailed: Boolean,
    onBack: () -> Unit,
    /** Vorschlag V-6 (2026-08-29): lädt die Liste neu, ohne den Bildschirm zu verlassen. */
    onRetry: () -> Unit,
    onToggleFrozen: (packageName: String, frozen: Boolean) -> Unit,
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_management_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.app_management_intro),
                style = MaterialTheme.typography.bodySmall,
            )
            if (loadFailed) {
                // Unterscheidet "wirklich keine Apps installiert" von "Liste konnte nicht gelesen
                // werden" (2026-08-21, real gefunden: ohne aktiven Device Owner wirft
                // AppFreezeManager.isFrozen() für jede Zeile eine SecurityException, die vorher
                // stillschweigend zu einer leeren Liste degradiert wurde — nicht von einer
                // tatsächlich leeren Liste unterscheidbar). Häufigste Ursache: kein Device Owner
                // aktiv, s. Statusanzeige "DO NICHT aktiv" auf dem vorherigen Bildschirm.
                ErrorStateRow(
                    headline = stringResource(R.string.network_app_list_unreadable_headline),
                    detail = stringResource(R.string.security_scanner_no_device_owner_detail),
                    onRetry = onRetry,
                )
            }
            SystemAppFilterRow(showSystemApps = showSystemApps, onShowSystemAppsChange = { showSystemApps = it })
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.network_search_label)) },
                singleLine = true,
                // Vorschlag V-4 (2026-08-29): dieselbe Leeren-Schaltfläche wie in der
                // Safeguard-Suche — eine Suche auf einem Touch-Gerät zeichenweise zurückzulöschen
                // ist der unnötigste Teil dieses Bildschirms.
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        TextButton(onClick = { query = "" }) { Text(stringResource(R.string.safeguards_search_clear)) }
                    }
                },
            )
            // Vorschlag V-4: Trefferzahl. Ohne sie war nicht zu sehen, wie viele Apps der
            // Systemapp-Filter gerade ausblendet — auf einem realen Gerät sind das die meisten.
            Text(
                text = String.format(stringResource(R.string.app_management_count), filtered.size, apps.size) +
                    if (!showSystemApps) stringResource(R.string.app_management_count_system_hidden_suffix) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            if (filtered.isEmpty() && !loadFailed) {
                // Vorher blieb hier eine leere Fläche stehen — nicht davon zu unterscheiden, dass
                // der Bildschirm hängt oder das Laden fehlgeschlagen ist.
                EmptyStateRow(
                    headline = if (query.isBlank()) {
                        String.format(
                            stringResource(R.string.app_management_empty_selection_headline),
                            if (!showSystemApps) {
                                stringResource(R.string.app_management_empty_selection_system_hidden_suffix)
                            } else {
                                stringResource(R.string.app_management_empty_selection_plain_suffix)
                            },
                        )
                    } else {
                        String.format(stringResource(R.string.app_management_no_match_headline), query)
                    },
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    AppManagementRow(
                        app = app,
                        onToggle = { frozen -> onToggleFrozen(app.packageName, frozen) },
                    )
                }
            }
        }
    }
}

/**
 * Punkt 9 ("weitere App-UI-Verschönerungen", 2026-08-22) — Wischen (in beide Richtungen) schaltet
 * dasselbe Einfrieren/Aktivieren wie der Switch um, kein neuer Risikofall: der Switch war schon
 * vorher ein einzelner Tap ohne Bestätigungsdialog, Wischen löst also keine folgenreichere Aktion
 * aus als ohnehin schon möglich war. `confirmValueChange` gibt immer `false` zurück — die Zeile
 * wird nie tatsächlich aus der Liste entfernt (anders als der übliche "Wischen = löschen"-Zweck
 * von `SwipeToDismissBox`), sie schnappt nach jedem vollständigen Wisch in den `Settled`-Zustand
 * zurück und bleibt wiederholt wischbar. Für geschützte Apps komplett deaktiviert
 * (`enableDismissFromStartToEnd`/`EndToStart = !app.protected`), passend zum bereits deaktivierten
 * Switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppManagementRow(
    app: AppManagementInfo,
    onToggle: (Boolean) -> Unit,
) {
    // `confirmValueChange` ist als Parameter mittlerweile deprecated (ohne direkten Ersatz, s.
    // Compilerhinweis — die empfohlene Migration auf dynamische Anchors ist für diesen simplen
    // "immer zurückschnappen"-Zweck deutlich komplexer als der aktuelle, weiterhin voll
    // funktionsfähige Callback) — bewusst beibehalten statt für eine Kosmetik-Ergänzung eine
    // größere, noch unausgereift dokumentierte API zu übernehmen.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled && !app.protected) {
                onToggle(!app.frozen)
            }
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = !app.protected,
        enableDismissFromEndToStart = !app.protected,
        backgroundContent = { AppSwipeActionBackground(dismissState = dismissState, frozen = app.frozen) },
    ) {
        AppManagementRowContent(app = app, onToggle = onToggle)
    }
}

@Composable
private fun AppSwipeActionBackground(dismissState: SwipeToDismissBoxState, frozen: Boolean) {
    val label = if (frozen) stringResource(R.string.app_management_swipe_activate) else stringResource(R.string.app_management_swipe_freeze)
    val alignment = when (dismissState.dismissDirection) {
        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
        SwipeToDismissBoxValue.Settled -> Alignment.Center
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun AppManagementRowContent(
    app: AppManagementInfo,
    onToggle: (Boolean) -> Unit,
) {
    val baseContentDescription = String.format(stringResource(R.string.app_management_row_content_description), app.label, app.packageName)
    val protectedSuffix = stringResource(R.string.app_management_row_protected_suffix)
    val stateFrozen = stringResource(R.string.app_management_row_state_frozen)
    val stateActive = stringResource(R.string.app_management_row_state_active)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .heightIn(min = 48.dp)
            // Vorschlag V-5 (2026-08-29), dasselbe Problem wie U-7 in MenuComponents: ein
            // `semantics {}` ohne `mergeDescendants` *ersetzt* die Kinderknoten nicht, es tritt
            // neben sie — eine Vorlesehilfe las hier nacheinander die Beschreibung, den App-Namen
            // und den (zeichenweise vorgelesenen) Paketnamen. Mit `mergeDescendants = true` bleibt
            // eine Zeile ein Knoten; der Switch ist ein eigener anklickbarer Semantikknoten und
            // wird deshalb weiterhin nicht mit eingezogen, bleibt also einzeln erreichbar.
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(baseContentDescription)
                    if (app.protected) append(protectedSuffix)
                }
                stateDescription = if (app.frozen) stateFrozen else stateActive
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
            Text(text = app.packageName, style = MaterialTheme.typography.bodySmall.mono())
            if (app.protected) {
                Text(
                    text = stringResource(R.string.app_management_protected_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Switch(
            checked = app.frozen,
            onCheckedChange = onToggle,
            enabled = !app.protected,
        )
    }
}
