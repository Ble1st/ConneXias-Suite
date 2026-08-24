package de.ble1st.warden.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Untermenü "Namensvergebung" (2026-08-22, auf Nutzerwunsch aus [SettingsScreen] herausgelöst) —
 * bündelt die drei Freitext-/DPM-Felder, die alle denselben Zweck haben (der Nutzerin bzw. einer
 * Finderin sichtbaren Text zuweisen): Sperrbildschirm-Zusatztext ([LockScreenMessageField]/
 * [de.ble1st.warden.registry.LockScreenInfoManager]), Organisationsname ([OrganizationNameField]/
 * [de.ble1st.warden.registry.OrganizationNameManager]) und Support-/Kontakthinweis
 * ([SupportMessageField]/[de.ble1st.warden.registry.SupportMessageManager]). Vorher lagen sie als
 * "Sperrbildschirm"-Abschnitt direkt in [SettingsScreen] — mit dem Auto-Reboot-Feld als viertem
 * Abschnitt wurde die Einstellungen-Seite zu voll für eine flache Liste, daher jetzt eigene
 * Unterseite statt eines weiteren, immer sichtbaren Abschnitts (dieselbe "eigene Unterseite bei
 * wachsender Liste"-Logik wie schon für [SettingsScreen] selbst gegenüber dem Dashboard).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NamingSettingsScreen(
    lockScreenText: String?,
    onLockScreenTextChange: (String?) -> Unit,
    organizationName: String?,
    onOrganizationNameChange: (String?) -> Unit,
    supportMessage: String?,
    onSupportMessageChange: (String?) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Namensvergebung") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            LockScreenMessageField(
                initialValue = lockScreenText,
                onSave = onLockScreenTextChange,
            )
            OrganizationNameField(
                initialValue = organizationName,
                onSave = onOrganizationNameChange,
                modifier = Modifier.padding(top = 20.dp),
            )
            SupportMessageField(
                initialValue = supportMessage,
                onSave = onSupportMessageChange,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}
