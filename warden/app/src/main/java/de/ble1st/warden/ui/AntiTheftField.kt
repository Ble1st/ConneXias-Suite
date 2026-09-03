package de.ble1st.warden.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ble1st.warden.R

/**
 * Settings-Feld für [de.ble1st.warden.antitheft.AntiTheftAlarmController] (2026-09-03, Ideenliste
 * Punkte 3+4) — zwei unabhängige Schalter statt einer Reaktions-Preset-Liste wie
 * [CellSecurityField]/[WifiTrustField], s. [de.ble1st.warden.domain.antitheft.AntiTheftConfig]
 * -Klassendoc für die Begründung.
 */
@Composable
fun AntiTheftField(
    motionAlarmEnabled: Boolean,
    onMotionAlarmChange: (Boolean) -> Unit,
    chargerAlarmEnabled: Boolean,
    onChargerAlarmChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = stringResource(R.string.anti_theft_field_title), style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(R.string.anti_theft_field_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.anti_theft_field_motion_label), style = MaterialTheme.typography.bodyLarge)
            }
            Switch(checked = motionAlarmEnabled, onCheckedChange = onMotionAlarmChange)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.anti_theft_field_charger_label), style = MaterialTheme.typography.bodyLarge)
            }
            Switch(checked = chargerAlarmEnabled, onCheckedChange = onChargerAlarmChange)
        }
    }
}
