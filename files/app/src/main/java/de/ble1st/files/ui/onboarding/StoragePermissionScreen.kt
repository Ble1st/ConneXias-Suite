package de.ble1st.files.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.ble1st.files.R

/**
 * Startbildschirm, solange [de.ble1st.files.permission.StoragePermission.hasFullAccess] false
 * liefert. Erklärt bewusst, wofür die Berechtigung gebraucht wird, bevor der Systemdialog
 * aufgeht — eine kommentarlose Sonderberechtigungs-Anfrage direkt beim ersten App-Start wäre für
 * die meisten Nutzer nicht nachvollziehbar (MANAGE_EXTERNAL_STORAGE hat keinen normalen
 * Runtime-Permission-Dialogtext, der das von selbst erklärt).
 *
 * Nach einer Ablehnung (Legacy-Pfad API < 30: "Nicht mehr fragen"; Settings-Pfad API 30+:
 * Zurückkehren ohne Gewährung) schaltet [permanentlyDenied] auf den Einstellungen-Tief-Link um.
 */
@Composable
fun StoragePermissionScreen(
    permanentlyDenied: Boolean = false,
    onRequestAccess: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = stringResource(R.string.onboarding_title),
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(id = R.string.onboarding_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (permanentlyDenied) stringResource(id = R.string.onboarding_denied_body)
                       else stringResource(id = R.string.onboarding_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (permanentlyDenied) {
                Button(onClick = onOpenSettings) {
                    Text(stringResource(id = R.string.onboarding_action_open_settings))
                }
            } else {
                Button(onClick = onRequestAccess) {
                    Text(stringResource(id = R.string.onboarding_action_grant))
                }
            }
        }
    }
}
