package de.ble1st.warden.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.ble1st.warden.ui.theme.WardenAccent

/**
 * "Frei wählbar"-Teil des Terminal-Themes (s. [de.ble1st.warden.ui.theme.WardenTheme]-Klassendoc):
 * eine kleine, feste Auswahl klassischer Phosphor-Monitor-Farben statt eines vollen Farbwählers —
 * genügt für den beabsichtigten Zweck (Akzent umschalten) ohne eine zusätzliche
 * Color-Picker-Bibliothek einzubinden, und jede Option bleibt garantiert kontrastsicher, weil sie
 * vorab in [WardenAccent] festgelegt ist statt aus beliebigem Nutzer-Input erzeugt zu werden.
 *
 * Seit "weitere App-UI-Verschönerungen" (2026-08-22, Punkt 5) eine offizielle
 * `SingleChoiceSegmentedButtonRow`/`SegmentedButton`-Gruppe statt handgebauter, einzeln
 * geclickter Kreise mit selbst gezeichnetem Auswahl-Rand — die Farbvorschau selbst (der ganze
 * Zweck dieser Zeile) bleibt erhalten, als kleiner farbiger Kreis im `icon`-Slot jedes Segments;
 * Auswahl-Zustand, verbundene Form und Ripple kommen jetzt aus der Standardkomponente statt aus
 * eigenem `border`/`clickable`-Code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccentPickerRow(selected: WardenAccent, onSelect: (WardenAccent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = "Akzentfarbe", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            WardenAccent.entries.forEachIndexed { index, accent ->
                SegmentedButton(
                    selected = accent == selected,
                    onClick = { onSelect(accent) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = WardenAccent.entries.size),
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(accent.color),
                        )
                    },
                    modifier = Modifier.semantics { contentDescription = "Akzentfarbe ${accent.label}" },
                ) {
                    Text(text = accent.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
