package de.ble1st.warden.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/**
 * Milestone "App-Verwaltung: Einfrieren/Deaktivieren" — portiert aus Heralds UI (jetzt Wardens
 * eigener In-App-Bildschirm statt einer separaten APK, s. Plan-Abschnitt "Herald-UI einbauen").
 * Ein gemeinsamer "Systemapps anzeigen"-Filter für [AppManagementScreen] und
 * [SecurityScannerScreen]s Geschwister-Aufbau, statt zweier unabhängiger, driftender
 * Implementierungen. Standardmäßig ausgeblendet — die deutliche Mehrheit von Nutzer-relevanten
 * Fällen (App einfrieren) betrifft installierte Fremd-Apps, nicht Systemkomponenten; die volle
 * `QUERY_ALL_PACKAGES`-Sicht bleibt einen Tap entfernt.
 */
@Composable
fun SystemAppFilterRow(showSystemApps: Boolean, onShowSystemAppsChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Systemapps anzeigen"
                stateDescription = if (showSystemApps) "an" else "aus"
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Systemapps anzeigen", style = MaterialTheme.typography.bodyMedium)
        Switch(checked = showSystemApps, onCheckedChange = onShowSystemAppsChange)
    }
}
