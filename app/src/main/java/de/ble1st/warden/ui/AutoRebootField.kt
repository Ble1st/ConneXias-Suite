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

/**
 * Settings-Feld für [de.ble1st.warden.autoreboot.AutoRebootController] (2026-08-22, auf
 * Nutzerwunsch: "ein zeitfenster für autoreboot nach vergangener zeit nach letztem entsperren des
 * geräts"). Seit "Stundenauswahl wie bei GrapheneOS" (2026-08-22) eine **Einfachauswahl-Liste**
 * mit festen Presets statt eines Zahlen-Eingabefelds — genau das Muster, das GrapheneOS für
 * dieselbe Einstellung verwendet (ein `ListPreference`-Dialog, kein Freitext): ein Tap wählt und
 * schreibt sofort, kein separater "Speichern"-Schritt mehr nötig.
 *
 * `onSelect(null)` deaktiviert die Funktion. `onSelect(hours)` bei einem vorherigen `null`-Zustand
 * (Aktivierung) **muss** vom Aufrufer zusätzlich sofort die Baseline setzen
 * ([de.ble1st.warden.autoreboot.AutoRebootStorage.saveLastSeenUnlockedMillis] auf
 * `System.currentTimeMillis()`) — s. [de.ble1st.warden.autoreboot.AutoRebootController]-Klassendoc
 * für die Begründung. Diese Composable selbst kennt nur den Schwellwert, nicht die Baseline.
 */
@Composable
fun AutoRebootField(selectedHours: Int?, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = "Auto-Reboot nach Inaktivität", style = MaterialTheme.typography.labelLarge)
        Text(
            text = "Startet das Gerät automatisch neu, wenn es ununterbrochen länger als die " +
                "gewählte Zeitspanne gesperrt bleibt (z. B. bei Verlust/Diebstahl) — versetzt es " +
                "zurück in den Vor-Entsperr-Zustand.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        AUTO_REBOOT_PRESETS_HOURS.forEach { hours ->
            val label = autoRebootPresetLabel(hours)
            val isSelected = hours == selectedHours
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(hours) }
                    .padding(vertical = 10.dp)
                    .semantics {
                        contentDescription = label
                        selected = isSelected
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = { onSelect(hours) })
                Text(text = label, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Presets analog zu GrapheneOS' Auto-Reboot-Liste — `null` = "Aus". */
private val AUTO_REBOOT_PRESETS_HOURS: List<Int?> = listOf(null, 12, 18, 24, 48, 72, 168)

private fun autoRebootPresetLabel(hours: Int?): String = when (hours) {
    null -> "Aus"
    24 -> "Nach 1 Tag"
    48 -> "Nach 2 Tagen"
    72 -> "Nach 3 Tagen"
    168 -> "Nach 1 Woche"
    else -> "Nach $hours Stunden"
}
