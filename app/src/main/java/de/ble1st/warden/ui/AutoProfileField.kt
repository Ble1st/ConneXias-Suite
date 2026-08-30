package de.ble1st.warden.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import de.ble1st.warden.domain.profile.AutoProfileConfig
import de.ble1st.warden.domain.profile.WardenProfile

/**
 * Settings-Feld für [de.ble1st.warden.profile.AutoProfileController] (2026-08-28) — die drei
 * Profile waren bis dahin rein manuell: kein Zeitplan, keine Bindung an die Bedrohungslage, und
 * trotz vorhandener `CRITICAL`-Erkennung keine Stufe zwischen "nichts" und dem Kiosk-Modus.
 *
 * Bewusst schmal gehalten: ein Schalter für die Bedrohungs-Eskalation und ein Nachtfenster mit
 * festen Stunden-Presets statt eines vollen Zeitpickers — die Genauigkeit des periodischen Laufs
 * liegt ohnehin bei 15 Minuten (s. [de.ble1st.warden.profile.AutoProfileWorker]), eine
 * minutengenaue Eingabe würde eine Präzision suggerieren, die es nicht gibt.
 */
@Composable
fun AutoProfileField(
    config: AutoProfileConfig,
    onChange: (AutoProfileConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = stringResource(R.string.auto_profile_field_title), style = MaterialTheme.typography.labelLarge)
        Text(
            text = stringResource(R.string.auto_profile_field_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.auto_profile_field_escalate_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 12.dp),
            )
            Switch(
                checked = config.escalateOnCriticalThreat,
                onCheckedChange = { onChange(config.copy(escalateOnCriticalThreat = it)) },
            )
        }

        ProfileChoiceRow(
            title = stringResource(R.string.auto_profile_field_night_title),
            options = listOf(null, WardenProfile.REISE, WardenProfile.MAXIMAL),
            selectedProfile = config.nightProfile,
            onSelect = { onChange(config.copy(nightProfile = it)) },
        )
        ProfileChoiceRow(
            title = stringResource(R.string.auto_profile_field_day_title),
            options = listOf(null, WardenProfile.ALLTAG, WardenProfile.REISE),
            selectedProfile = config.dayProfile,
            onSelect = { onChange(config.copy(dayProfile = it)) },
        )

        if (config.nightProfile != null || config.dayProfile != null) {
            HourChoiceRow(
                title = stringResource(R.string.auto_profile_field_night_start_title),
                hours = listOf(20, 21, 22, 23),
                selectedMinuteOfDay = config.nightStartMinuteOfDay,
                onSelect = { onChange(config.copy(nightStartMinuteOfDay = it)) },
            )
            HourChoiceRow(
                title = stringResource(R.string.auto_profile_field_night_end_title),
                hours = listOf(5, 6, 7, 8),
                selectedMinuteOfDay = config.nightEndMinuteOfDay,
                onSelect = { onChange(config.copy(nightEndMinuteOfDay = it)) },
            )
        }
    }
}

@Composable
private fun ProfileChoiceRow(
    title: String,
    options: List<WardenProfile?>,
    // Nicht "selected": innerhalb von semantics { } würde der Parameter die gleichnamige
    // Semantics-Eigenschaft verdecken.
    selectedProfile: WardenProfile?,
    onSelect: (WardenProfile?) -> Unit,
) {
    val noSwitchLabel = stringResource(R.string.auto_profile_field_no_switch_label)
    val contentDescriptionTemplate = stringResource(R.string.auto_profile_field_choice_content_description)
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        options.forEach { profile ->
            val label = profile?.label ?: noSwitchLabel
            val isSelected = profile == selectedProfile
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(profile) }
                    .padding(vertical = 8.dp)
                    .semantics {
                        contentDescription = String.format(contentDescriptionTemplate, title, label)
                        selected = isSelected
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = { onSelect(profile) })
                Text(text = label, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun HourChoiceRow(
    title: String,
    hours: List<Int>,
    selectedMinuteOfDay: Int,
    onSelect: (Int) -> Unit,
) {
    val contentDescriptionTemplate = stringResource(R.string.auto_profile_field_hour_content_description)
    val hourLabelTemplate = stringResource(R.string.auto_profile_field_hour_label)
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            hours.forEach { hour ->
                val minuteOfDay = hour * 60
                val isSelected = minuteOfDay == selectedMinuteOfDay
                val hourLabel = String.format(hourLabelTemplate, hour)
                Row(
                    modifier = Modifier
                        .clickable { onSelect(minuteOfDay) }
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                        .semantics {
                            contentDescription = String.format(contentDescriptionTemplate, title, hour)
                            selected = isSelected
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = { onSelect(minuteOfDay) })
                    Text(text = hourLabel, modifier = Modifier.padding(start = 2.dp))
                }
            }
        }
    }
}
