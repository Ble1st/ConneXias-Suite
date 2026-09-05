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
import de.ble1st.warden.domain.appmanagement.FreezeMethod
import de.ble1st.warden.domain.hardening.FailedAttemptsWipeThreshold
import de.ble1st.warden.domain.hardening.LocationEnforcement
import de.ble1st.warden.domain.hardening.TimeIntegrityMode

/**
 * Gemeinsamer Rumpf der vier Tier-2-Auswahlmenüs (2026-09-05, Nutzerwunsch "Tier 2 komplett mit
 * Auswahlmenüs für alle").
 *
 * **Warum hier eine geteilte Komponente steht, obwohl die bestehenden Felder
 * ([CellSecurityField], [SimChangeField], [WifiTrustField]) jeweils ihre eigene Kopie derselben
 * Radio-Liste tragen:** vier weitere identische Kopien wären der Punkt, an dem aus einem
 * vertretbaren Muster eine Wartungslast wird — eine Änderung an der Barrierefreiheits-Semantik
 * müsste dann an sieben Stellen nachgezogen werden. Die bestehenden drei werden bewusst **nicht**
 * mit umgestellt: das wäre eine Refaktorierung ohne Anlass in Dateien, an denen diese Änderung
 * sonst nichts zu tun hat (dieselbe Zurückhaltung wie bei der `strings.xml`-Extraktion, die
 * vorhandene Texte unverändert übernahm).
 */
@Composable
private fun <T> ChoiceField(
    title: String,
    description: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    footnote: String? = null,
) {
    Column(modifier = modifier) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        options.forEach { option ->
            val label = labelOf(option)
            val isSelected = option == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(option) }
                    .padding(vertical = 10.dp)
                    .semantics {
                        contentDescription = label
                        this.selected = isSelected
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = { onSelect(option) })
                Text(text = label, modifier = Modifier.padding(start = 8.dp))
            }
        }
        footnote?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun FreezeMethodField(
    selected: FreezeMethod,
    onSelect: (FreezeMethod) -> Unit,
    modifier: Modifier = Modifier,
) = ChoiceField(
    title = stringResource(R.string.freeze_method_field_title),
    description = stringResource(R.string.freeze_method_field_description),
    options = FreezeMethod.entries,
    selected = selected,
    labelOf = { it.label },
    onSelect = onSelect,
    modifier = modifier,
)

@Composable
fun LocationEnforcementField(
    selected: LocationEnforcement,
    onSelect: (LocationEnforcement) -> Unit,
    modifier: Modifier = Modifier,
) = ChoiceField(
    title = stringResource(R.string.location_enforcement_field_title),
    description = stringResource(R.string.location_enforcement_field_description),
    options = LocationEnforcement.entries,
    selected = selected,
    labelOf = { it.label },
    onSelect = onSelect,
    modifier = modifier,
)

@Composable
fun TimeIntegrityField(
    selected: TimeIntegrityMode,
    onSelect: (TimeIntegrityMode) -> Unit,
    modifier: Modifier = Modifier,
) = ChoiceField(
    title = stringResource(R.string.time_integrity_field_title),
    description = stringResource(R.string.time_integrity_field_description),
    options = TimeIntegrityMode.entries,
    selected = selected,
    labelOf = { it.label },
    onSelect = onSelect,
    modifier = modifier,
)

/** Trägt als einziges der vier eine Fußnote in Fehlerfarbe — s.
 * [FailedAttemptsWipeThreshold]-Klassendoc: das ist der einzige unumkehrbare Schalter im ganzen
 * Einstellungsbildschirm und steht bewusst im Widerspruch zur sonstigen "Neustatt statt Löschen"-
 * Linie des Projekts. Eine Warnung, die man beim Auswählen sieht, ist hier angemessener als ein
 * Bestätigungsdialog, den man wegklickt. */
@Composable
fun FailedAttemptsWipeField(
    selected: FailedAttemptsWipeThreshold,
    onSelect: (FailedAttemptsWipeThreshold) -> Unit,
    modifier: Modifier = Modifier,
) = ChoiceField(
    title = stringResource(R.string.failed_attempts_wipe_field_title),
    description = stringResource(R.string.failed_attempts_wipe_field_description),
    options = FailedAttemptsWipeThreshold.entries,
    selected = selected,
    labelOf = { it.label },
    onSelect = onSelect,
    modifier = modifier,
    footnote = if (selected.isEnabled) stringResource(R.string.failed_attempts_wipe_field_warning) else null,
)
