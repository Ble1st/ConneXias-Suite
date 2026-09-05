package de.ble1st.warden.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ble1st.warden.R
import de.ble1st.warden.admin.OwnershipTransferTarget
import de.ble1st.warden.admin.OwnershipTransferTargetReader
import de.ble1st.warden.domain.presence.SensitiveAction
import de.ble1st.warden.presence.SensitiveActionActivity
import de.ble1st.warden.ui.theme.mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Erweitert" — der Ort für die wenigen Funktionen, die man nicht beim Durchscrollen der normalen
 * Einstellungen finden soll (Tier 3 der DPC-Recherche, 2026-09-05, Nutzerwunsch "changeowner in
 * Erweitert").
 *
 * Aktuell steht hier genau eine: die Übertragung der Device-Owner-Rolle. Sie ist bewusst *nicht*
 * neben den Härtungs-Auswahlmenüs gelandet — die ändern eine Einstellung, diese hier gibt die
 * Grundlage auf, auf der alle anderen überhaupt wirken. Eine eigene, klar benannte Ebene mit einem
 * eigenen Zurückweg ist die ehrlichere Einordnung als eine weitere Zeile in einer langen Liste.
 *
 * **Ausgeführt wird hier nichts.** Der Bildschirm wählt nur das Ziel und übergibt dann an
 * [SensitiveActionActivity] — dieselbe Kette aus Debug-Build-Guard, Rate-Limit, exakt getipptem
 * Bestätigungstext und frischem Presence-Nachweis wie bei jeder anderen destruktiven Aktion. Ein
 * zweiter, eigener Ausführungspfad hier wäre genau die Art Nebentür, die dieses Projekt an anderer
 * Stelle als echten Bug gefunden hat.
 *
 * Zielliste und Device-Owner-Status werden hier selbst gelesen statt über
 * [de.ble1st.warden.ui.WardenStatusActivity] hereingereicht — wie bei den anderen Unterseiten des
 * Einstellungsbildschirms ([NamingSettingsScreen], [LicensesScreen]) gibt es dafür keinen zweiten
 * Einstiegspunkt, der denselben Zustand bräuchte. Der Lesevorgang läuft trotzdem auf
 * `Dispatchers.IO`: `queryBroadcastReceivers` über alle installierten Pakete ist kein Main-Thread-
 * Aufruf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var targets by remember { mutableStateOf<List<OwnershipTransferTarget>?>(null) }

    LaunchedEffect(Unit) {
        targets = withContext(Dispatchers.IO) {
            runCatching { OwnershipTransferTargetReader(context).availableTargets() }.getOrNull()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.advanced_settings_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SectionLabel(stringResource(R.string.advanced_settings_section_ownership))
            Text(
                text = stringResource(R.string.advanced_settings_ownership_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            when {
                // `null` heißt "Lesen fehlgeschlagen", nicht "keine Ziele" — die beiden dürfen sich
                // hier so wenig vermischen wie überall sonst in diesem Projekt.
                targets == null -> Text(
                    stringResource(R.string.advanced_settings_ownership_targets_unreadable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                targets.orEmpty().isEmpty() -> Text(
                    stringResource(R.string.advanced_settings_ownership_no_targets),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> targets.orEmpty().forEach { target ->
                    MenuRow(
                        title = target.label,
                        subtitle = target.receiver.flattenToShortString(),
                        tag = "DO",
                        onClick = { startTransferConfirmation(context, target) },
                    )
                }
            }

            Text(
                text = stringResource(R.string.advanced_settings_ownership_footnote),
                style = MaterialTheme.typography.bodySmall.mono(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

private fun startTransferConfirmation(context: Context, target: OwnershipTransferTarget) {
    context.startActivity(
        Intent(context, SensitiveActionActivity::class.java)
            .putExtra(SensitiveActionActivity.EXTRA_PRESELECTED_ACTION, SensitiveAction.TRANSFER_OWNERSHIP.name)
            .putExtra(SensitiveActionActivity.EXTRA_TRANSFER_TARGET, target.receiver.flattenToString()),
    )
}
