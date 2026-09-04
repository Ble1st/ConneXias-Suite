package de.ble1st.warden.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.ble1st.warden.R
import de.ble1st.warden.domain.wifitrust.WifiTrustReaction

/**
 * Settings-Feld für [de.ble1st.warden.wifitrust.WifiTrustController] (2026-09-03) — dieselbe
 * Reaktions-Preset-Liste wie [CellSecurityField] ("Aus" plus die Stufen aus [WifiTrustReaction]),
 * ergänzt um die vom Nutzer geführte Liste vertrauter SSIDs (Eingabe-/Entfernen-Muster von
 * [de.ble1st.warden.ui.NetworkScreen]s Domain-Blockliste übernommen).
 *
 * [currentSsid] ist nur ein UI-Komfort — ein Schnell-hinzufügen-Knopf für das gerade verbundene
 * Netz, damit der Nutzer die SSID nicht selbst abtippen muss. `null` (kein WLAN verbunden, oder
 * nicht ermittelbar) blendet den Knopf einfach aus statt einen Platzhalter zu zeigen; ist die
 * aktuelle SSID bereits in der Liste, ebenfalls kein Knopf.
 */
@Composable
fun WifiTrustField(
    selectedReaction: WifiTrustReaction?,
    onSelectReaction: (WifiTrustReaction?) -> Unit,
    trustedSsids: Set<String>,
    onAddSsid: (String) -> Unit,
    onRemoveSsid: (String) -> Unit,
    currentSsid: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = stringResource(R.string.wifi_trust_field_title), style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(R.string.wifi_trust_field_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        val offLabel = stringResource(R.string.field_off_label)
        WIFI_TRUST_OPTIONS.forEach { reaction ->
            val label = reaction?.label ?: offLabel
            val isSelected = reaction == selectedReaction
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectReaction(reaction) }
                    .padding(vertical = 10.dp)
                    .semantics {
                        contentDescription = label
                        selected = isSelected
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = { onSelectReaction(reaction) })
                Text(text = label, modifier = Modifier.padding(start = 8.dp))
            }
        }

        Text(
            text = stringResource(R.string.wifi_trust_list_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )

        if (currentSsid != null && currentSsid !in trustedSsids) {
            TextButton(onClick = { onAddSsid(currentSsid) }) {
                Text(String.format(stringResource(R.string.wifi_trust_add_current_action), currentSsid))
            }
        }

        var draft by remember { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.wifi_trust_add_ssid_label)) },
                singleLine = true,
            )
            TextButton(
                onClick = {
                    onAddSsid(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
            ) { Text(stringResource(R.string.wifi_trust_add_ssid_action)) }
        }

        trustedSsids.sorted().forEach { ssid ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = ssid, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemoveSsid(ssid) }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = String.format(stringResource(R.string.wifi_trust_remove_ssid_content_description), ssid),
                    )
                }
            }
        }
    }
}

private val WIFI_TRUST_OPTIONS: List<WifiTrustReaction?> = listOf(null) + WifiTrustReaction.entries
