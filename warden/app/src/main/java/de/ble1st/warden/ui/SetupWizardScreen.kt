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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import de.ble1st.warden.R
import de.ble1st.warden.domain.setup.SetupStep
import de.ble1st.warden.domain.setup.SetupWizardState

/**
 * Ersteinrichtungs-Assistent (Finalisierung 2026-09-03). Bis dahin landete jeder frisch
 * provisionierte Device Owner direkt auf dem Dashboard — mit 32 Safeguards, drei Profilen, PIN,
 * Sentinel und Notruf-Drill nebeneinander im Menü und ohne jeden Hinweis, dass genau zwei davon
 * (Device Owner und PIN) darüber entscheiden, ob die App überhaupt etwas schützt. Wer die
 * PIN-Einrichtung überging, hatte eine App, die aussah, als arbeite sie, deren WardenLock aber
 * ein No-Op war.
 *
 * Der Bildschirm ist bewusst **eine Übersicht mit Verweisen und kein zweiter Bedienweg**: jeder
 * Schritt springt in genau die Stelle, an der die Funktion ohnehin lebt (PIN-Activity,
 * Safeguards-Bildschirm). Eine Zweitimplementierung — etwa eine eigene Profil-Auswahl hier — wäre
 * ein zweiter Pfad, der beim nächsten Umbau der Originalstelle still veraltet; beim Notruf-Drill
 * käme hinzu, dass dessen Bestätigung an einen exakt einzutippenden Text gebunden ist und genau
 * diese Hürde nicht in einem Assistenten verdünnt werden darf (s.
 * [de.ble1st.warden.pin.WardenLockTaskDrillStorage]).
 *
 * Der Status jedes Schritts kommt bei jedem Öffnen frisch aus dem echten Systemzustand, nicht aus
 * einem mitgeführten Fortschritt (s. [de.ble1st.warden.setup.SetupWizardStore]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    state: SetupWizardState,
    onOpenPinSetup: () -> Unit,
    onOpenSafeguards: () -> Unit,
    onInstallSentinel: () -> Unit,
    onRefresh: () -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.setup_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            LinearProgressIndicator(
                progress = { state.doneCount / SetupStep.entries.size.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.setup_progress, state.doneCount, SetupStep.entries.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            for (step in SetupStep.entries) {
                StepCard(
                    step = step,
                    state = state,
                    onAction = when (step) {
                        SetupStep.DEVICE_OWNER -> null
                        SetupStep.PIN -> onOpenPinSetup
                        SetupStep.PROFILE -> onOpenSafeguards
                        SetupStep.SENTINEL -> onInstallSentinel
                        SetupStep.EMERGENCY_DRILL -> onOpenSafeguards
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // "Status prüfen" statt eines automatischen Live-Abgleichs: PIN, Sentinel und
                // Drill werden in anderen Activities gesetzt, aus denen es keinen Rückkanal
                // hierher gibt — dieselbe "informativ, nicht perfekt live"-Haltung wie beim
                // Sentinel-Status im Safeguards-Bildschirm.
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.setup_action_refresh)) }
                Button(onClick = onFinish) {
                    Text(
                        stringResource(
                            if (state.requiredComplete) R.string.setup_action_finish else R.string.setup_action_finish_incomplete,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepCard(step: SetupStep, state: SetupWizardState, onAction: (() -> Unit)?) {
    val done = state.isDone(step)
    val blocked = state.isBlocked(step)
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Das Symbol wiederholt nur, was die Statuszeile unten im Klartext sagt — für
            // Vorlesehilfen wäre es damit reine Doppelung.
            Icon(
                // Nur drei Symbole aus material-icons-core (Warden bindet bewusst nicht das
                // volle Icon-Set ein, s. libs.versions.toml).
                imageVector = when {
                    done -> Icons.Filled.CheckCircle
                    blocked -> Icons.Filled.Lock
                    else -> Icons.Filled.Info
                },
                contentDescription = null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "" },
            )
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = stringResource(step.titleRes), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(step.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        when {
                            done -> R.string.setup_state_done
                            blocked -> R.string.setup_state_blocked
                            step.required -> R.string.setup_state_open_required
                            else -> R.string.setup_state_open_optional
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!done && step.required) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (onAction != null && !blocked) {
                    TextButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(step.actionRes))
                    }
                }
            }
        }
    }
}

/** Die Texte liegen hier statt in [SetupStep] selbst — das Enum ist bewusst framework-frei (kein
 * `R`-Bezug), damit es unter einem reinen JVM-Test lädt. */
private val SetupStep.titleRes: Int
    get() = when (this) {
        SetupStep.DEVICE_OWNER -> R.string.setup_step_device_owner_title
        SetupStep.PIN -> R.string.setup_step_pin_title
        SetupStep.PROFILE -> R.string.setup_step_profile_title
        SetupStep.SENTINEL -> R.string.setup_step_sentinel_title
        SetupStep.EMERGENCY_DRILL -> R.string.setup_step_drill_title
    }

private val SetupStep.descriptionRes: Int
    get() = when (this) {
        SetupStep.DEVICE_OWNER -> R.string.setup_step_device_owner_description
        SetupStep.PIN -> R.string.setup_step_pin_description
        SetupStep.PROFILE -> R.string.setup_step_profile_description
        SetupStep.SENTINEL -> R.string.setup_step_sentinel_description
        SetupStep.EMERGENCY_DRILL -> R.string.setup_step_drill_description
    }

private val SetupStep.actionRes: Int
    get() = when (this) {
        SetupStep.DEVICE_OWNER -> R.string.setup_action_refresh
        SetupStep.PIN -> R.string.setup_step_pin_action
        SetupStep.PROFILE -> R.string.setup_step_profile_action
        SetupStep.SENTINEL -> R.string.setup_step_sentinel_action
        SetupStep.EMERGENCY_DRILL -> R.string.setup_step_drill_action
    }
