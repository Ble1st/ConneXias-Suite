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
import de.ble1st.warden.registry.WardenSupportMessageStorage

/**
 * Tier 6 ("Kosmetik", 2026-08-22) — Editor für [de.ble1st.warden.registry.SupportMessageManager]
 * (`setLongSupportMessage`), gleiches `draft`/"Speichern"-Muster wie [LockScreenMessageField]/
 * [OrganizationNameField] (dortiges Klassendoc für die Begründung, inklusive `rememberSaveable`
 * seit Architektur-Review 2026-08-24, F-3).
 */
@Composable
fun SupportMessageField(initialValue: String?, onSave: (String?) -> Unit, modifier: Modifier = Modifier) {
    var draft by rememberSaveable { mutableStateOf(initialValue.orEmpty()) }
    val changed = draft.trim() != initialValue.orEmpty()

    Column(modifier = modifier) {
        Text(text = stringResource(R.string.support_message_field_title), style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(R.string.support_message_field_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { if (it.length <= WardenSupportMessageStorage.MAX_LENGTH) draft = it },
            label = { Text(stringResource(R.string.support_message_field_label)) },
            supportingText = { Text(String.format(stringResource(R.string.field_character_counter), draft.length, WardenSupportMessageStorage.MAX_LENGTH)) },
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
