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
import de.ble1st.warden.domain.sim.SimChangeReaction

/**
 * Settings-Feld für [de.ble1st.warden.sim.SimChangeController] (2026-08-28) — dieselbe
 * Preset-Liste wie [AutoRebootField]/[FailedAttemptsRebootField], hier aber eine Auswahl der
 * *Reaktion* statt eines Schwellwerts: "Aus" plus die drei Stufen aus [SimChangeReaction].
 *
 * Der Hinweis auf den Alltagsfall ist Absicht: ein SIM-Wechsel ist nicht per se ein Diebstahl,
 * sondern oft nur ein neuer Vertrag oder eine Urlaubs-SIM. Wer "Neustart" wählt, soll wissen,
 * dass das eigene Umstecken denselben Reflex auslöst.
 */
@Composable
fun SimChangeField(
    selectedReaction: SimChangeReaction?,
    onSelect: (SimChangeReaction?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = stringResource(R.string.sim_change_field_title), style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(R.string.sim_change_field_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        val offLabel = stringResource(R.string.field_off_label)
        SIM_CHANGE_OPTIONS.forEach { reaction ->
            val label = reaction?.label ?: offLabel
            val isSelected = reaction == selectedReaction
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(reaction) }
                    .padding(vertical = 10.dp)
                    .semantics {
                        contentDescription = label
                        selected = isSelected
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = { onSelect(reaction) })
                Text(text = label, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private val SIM_CHANGE_OPTIONS: List<SimChangeReaction?> = listOf(null) + SimChangeReaction.entries
