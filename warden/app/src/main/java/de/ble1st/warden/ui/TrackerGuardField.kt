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
 * Settings-Feld für [de.ble1st.warden.tracker.BleTrackerController] (2026-09-03, Ideenliste
 * "BLE-Tracker-Wächter, auch AirTags") — ein einzelner Schalter, dasselbe Muster wie
 * [ClipboardCrossAppScreen]s Opt-in-Switch, kein Reaktions-Preset wie bei
 * [CellSecurityField]/[WifiTrustField]: es gibt hier keine Handlungsalternative außer melden.
 */
@Composable
fun TrackerGuardField(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = stringResource(R.string.tracker_guard_field_title), style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(R.string.tracker_guard_field_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.tracker_guard_field_switch_label), style = MaterialTheme.typography.bodyLarge)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}
