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
import de.ble1st.warden.pin.WardenLockScreenTextStorage

/**
 * Editor für den optionalen Zusatztext auf dem **echten OS-Sperrbildschirm** (Keyguard, nicht
 * Wardens eigenem Warden-PIN-Screen!) — z. B. "Dieses Gerät wird von deiner Organisation
 * verwaltet: <Text>". Eigene Zeile im [SettingsScreen], analog zu [AccentPickerRow]. Anwendung
 * über [de.ble1st.warden.registry.LockScreenInfoManager]
 * ([DevicePolicyManager.setDeviceOwnerLockScreenInfo][android.app.admin.DevicePolicyManager
 * .setDeviceOwnerLockScreenInfo]), Soll-Wert-Persistenz über
 * [de.ble1st.warden.pin.WardenLockScreenTextStorage] — s. beide Klassendocs.
 *
 * Eigener `draft`-Zustand statt sofortigem Zurückschreiben bei jeder Änderung (anders als
 * [AccentPickerRow], wo jede Auswahl ein einzelner, diskreter Klick ist): ein Textfeld tippt
 * zeichenweise, jeder Tastendruck würde sonst einen eigenen DPM-/Storage-Aufruf auslösen. Erst
 * der "Speichern"-Button committet — [onSave] wird genauso wie [AccentPickerRow.onSelect] vom
 * Aufrufer sofort appliziert+persistiert.
 *
 * `rememberSaveable` statt `remember` (Architektur-Review 2026-08-24, F-3): genau dieser
 * ungesicherte `draft` ist der einzige echte Datenverlust-Fall bei einer Konfigurationsänderung
 * (Rotation, Falt-/Split-Screen-Vorgang) in der ganzen Activity — anders als geladene Anzeigedaten
 * andernorts (die nach einem Recompose einfach neu gelesen werden) gibt es für einen noch nicht
 * gespeicherten Tipp-Fortschritt keine zweite Quelle.
 */
@Composable
fun LockScreenMessageField(initialValue: String?, onSave: (String?) -> Unit, modifier: Modifier = Modifier) {
    var draft by rememberSaveable { mutableStateOf(initialValue.orEmpty()) }
    val changed = draft.trim() != initialValue.orEmpty()

    Column(modifier = modifier) {
        Text(text = stringResource(R.string.lock_screen_message_field_title), style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(R.string.lock_screen_message_field_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { if (it.length <= WardenLockScreenTextStorage.MAX_LENGTH) draft = it },
            label = { Text(stringResource(R.string.lock_screen_message_field_label)) },
            supportingText = { Text(String.format(stringResource(R.string.field_character_counter), draft.length, WardenLockScreenTextStorage.MAX_LENGTH)) },
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
