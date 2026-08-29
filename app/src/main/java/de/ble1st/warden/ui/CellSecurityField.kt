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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.ble1st.warden.domain.cellsecurity.CellSecurityReaction

/**
 * Settings-Feld für [de.ble1st.warden.cellsecurity.CellSecurityController] (2026-08-29) —
 * dieselbe Preset-Liste wie [SimChangeField]: "Aus" plus die drei Stufen aus
 * [CellSecurityReaction].
 *
 * Der Hinweis auf die Unsicherheit ist Absicht (s. [de.ble1st.warden.domain.cellsecurity
 * .CellSecurityDecision]-Klassendoc): das ist ein Verdachts-Indikator, kein Beweis, und braucht
 * echte Feldverifikation, bevor man ihm blind vertraut.
 */
@Composable
fun CellSecurityField(
    selectedReaction: CellSecurityReaction?,
    onSelect: (CellSecurityReaction?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = "Reaktion auf Mobilfunkzellen-Auffälligkeiten", style = MaterialTheme.typography.labelLarge)
        Text(
            text = "Prüft regelmäßig die registrierte Mobilfunkzelle auf Anzeichen, die bei " +
                "einem IMSI-Catcher (Fake-Sendemast) auftreten können — z. B. eine plötzliche " +
                "Funkgenerations-Herabstufung oder ein widersprüchlicher Gebietscode. Das ist ein " +
                "Verdachtssignal, kein Beweis: Android erlaubt Apps keinen Zugriff auf das " +
                "Basisband, echte Fehlalarme (z. B. beim Fahren durch ein Funkloch) sind möglich.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        CELL_SECURITY_OPTIONS.forEach { reaction ->
            val label = reaction?.label ?: "Aus"
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

private val CELL_SECURITY_OPTIONS: List<CellSecurityReaction?> = listOf(null) + CellSecurityReaction.entries
