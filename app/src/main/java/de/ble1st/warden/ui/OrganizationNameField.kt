package de.ble1st.warden.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ble1st.warden.R
import de.ble1st.warden.registry.WardenOrganizationNameStorage

/**
 * Editor für den Organisationsnamen ([de.ble1st.warden.registry.OrganizationNameManager],
 * `DevicePolicyManager.setOrganizationName`) — eigenes Feld unterhalb von
 * [LockScreenMessageField] im [SettingsScreen], nicht dasselbe DPM-Feld: **live entdeckt
 * (2026-08-22)**, dass Wardens `setDeviceOwnerLockScreenInfo`-Freitext auf einem organization-
 * owned Testgerät (Samsung One UI) gar nicht auf dem echten Sperrbildschirm erschien — stattdessen
 * zeigte das OS dort fest "Dieses Gerät gehört deiner Organisation" (Default-Text ohne gesetzten
 * Organisationsnamen). Mit gesetztem Namen wird daraus "Dieses Gerät gehört zu \<Name\>" — dieses
 * Feld deckt genau das ab, s. [de.ble1st.warden.registry.OrganizationNameManager]-Klassendoc für
 * die volle Herleitung.
 *
 * Gleiches `draft`/"Speichern"-Muster wie [LockScreenMessageField] (dortiges Klassendoc für die
 * Begründung) — bewusst keine gemeinsame generische Komponente für beide Felder, weil sie zwei
 * unabhängige DPM-Werte mit eigenem Erklärtext ansteuern, keine austauschbaren Instanzen
 * desselben Konzepts.
 *
 * `rememberSaveable` statt `remember` (Architektur-Review 2026-08-24, F-3) — dieselbe Begründung
 * wie [LockScreenMessageField]s `draft`.
 */
@Composable
fun OrganizationNameField(initialValue: String?, onSave: (String?) -> Unit, modifier: Modifier = Modifier) {
    var draft by rememberSaveable { mutableStateOf(initialValue.orEmpty()) }
    val changed = draft.trim() != initialValue.orEmpty()

    Column(modifier = modifier) {
        Text(text = stringResource(R.string.organization_name_field_title), style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(R.string.organization_name_field_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { if (it.length <= WardenOrganizationNameStorage.MAX_LENGTH) draft = it },
            label = { Text(stringResource(R.string.organization_name_field_label)) },
            supportingText = { Text(String.format(stringResource(R.string.field_character_counter), draft.length, WardenOrganizationNameStorage.MAX_LENGTH)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            TextButton(
                onClick = {
                    val trimmed = draft.trim()
                    draft = trimmed
                    onSave(trimmed.ifEmpty { null })
                },
                enabled = changed,
            ) {
                Text(stringResource(R.string.action_save))
            }
            TextButton(
                onClick = { draft = ""; onSave(null) },
                enabled = draft.isNotEmpty() || !initialValue.isNullOrEmpty(),
            ) {
                Text(stringResource(R.string.action_delete))
            }
        }
    }
}
