package de.ble1st.warden.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.ble1st.warden.R

/**
 * Settings-Feld für [de.ble1st.warden.failedattempts.FailedAttemptsRebootController]
 * (2026-08-28) — bewusst dasselbe Preset-Listen-Muster wie [AutoRebootField] direkt darüber:
 * beide Felder beschreiben denselben Reflex (zurück in den BFU-Zustand), nur mit
 * unterschiedlichem Auslöser (Zeit ohne Entsperren vs. Fehlversuche am Sperrbildschirm).
 *
 * Der Warnhinweis zum fehlenden Sperrbildschirm-Zugangscode ist kein Beiwerk: ohne gesetzte
 * System-PIN/-Passwort gibt es überhaupt keine Fehlversuche, die Android melden könnte — die
 * Funktion wäre still wirkungslos, genau wie die FRP-Kontosperre ohne Google-Play-Dienste.
 */
@Composable
fun FailedAttemptsRebootField(
    selectedThreshold: Int?,
    secureLockScreenConfigured: Boolean,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = stringResource(R.string.failed_attempts_reboot_field_title), style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(R.string.failed_attempts_reboot_field_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        if (!secureLockScreenConfigured) {
            Text(
                text = stringResource(R.string.failed_attempts_reboot_field_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        FAILED_ATTEMPTS_PRESETS.forEach { threshold ->
            val label = failedAttemptsPresetLabel(threshold)
            val isSelected = threshold == selectedThreshold
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(threshold) }
                    .padding(vertical = 10.dp)
                    .semantics {
                        contentDescription = label
                        selected = isSelected
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = { onSelect(threshold) })
                Text(text = label, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** `null` = "Aus"; Untergrenze 3 entspricht [de.ble1st.warden.domain.failedattempts
 * .FailedAttemptsRebootDecision.MIN_THRESHOLD]. */
private val FAILED_ATTEMPTS_PRESETS: List<Int?> = listOf(null, 3, 5, 10, 15, 20)

@Composable
private fun failedAttemptsPresetLabel(threshold: Int?): String = when (threshold) {
    null -> stringResource(R.string.field_off_label)
    else -> String.format(stringResource(R.string.failed_attempts_reboot_field_preset), threshold)
}
