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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import de.ble1st.warden.appmanagement.AppManagementInfo

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
                title = { Text("App-Verwaltung") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Eingefroren = App verschwindet aus Launcher/Übersicht, bleibt aber " +
                    "installiert (Daten bleiben erhalten, jederzeit reversibel).",
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
                    headline = "App-Liste konnte nicht geladen werden",
                    detail = "Vermutlich kein Device Owner aktiv — s. Statusanzeige.",
                )
            }
            SystemAppFilterRow(showSystemApps = showSystemApps, onShowSystemAppsChange = { showSystemApps = it })
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Suchen") },
                singleLine = true,
            )
            HorizontalDivider()
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
    val label = if (frozen) "Aktivieren" else "Einfrieren"
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = "App-Verwaltung ${app.label}"
                stateDescription = if (app.frozen) "eingefroren" else "aktiv"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
            Text(text = app.packageName, style = MaterialTheme.typography.bodySmall)
            if (app.protected) {
                Text(
                    text = "Geschützt — kann nicht eingefroren werden",
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
