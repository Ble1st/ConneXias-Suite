package de.ble1st.files.ui.webdav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.ble1st.files.R
import de.ble1st.files.data.webdav.WebDavAccount
import de.ble1st.files.data.webdav.WebDavClient
import de.ble1st.files.data.webdav.WebDavEntry
import kotlinx.coroutines.launch

private sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data object Success : TestState
    data class Failed(val message: String) : TestState
}

/**
 * Server hinzufügen/bearbeiten. "Speichern" ist nicht an einen erfolgreichen Verbindungstest
 * gekoppelt — ein Server kann z. B. gerade offline sein, ohne dass man deshalb die
 * Zugangsdaten nicht schon hinterlegen dürfte.
 */
@Composable
fun WebDavAccountDialog(
    existing: WebDavAccount?,
    onSave: (WebDavAccount) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }
    val scope = rememberCoroutineScope()
    // Vorab aufgelöst: der Fallback wird im onClick-Lambda gebraucht, und stringResource() ist
    // eine @Composable-Funktion, die dort nicht aufgerufen werden darf.
    val connectionFailedMessage = stringResource(id = R.string.webdav_error_connection_failed)

    val isValid = label.isNotBlank() && baseUrl.isNotBlank() && username.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    id = if (existing == null) R.string.webdav_add_server else R.string.webdav_edit_server,
                ),
            )
        },
        text = {
            Column {
                OutlinedTextField(value = label, onValueChange = { label = it }, singleLine = true, label = { Text(stringResource(id = R.string.webdav_field_label)) })
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; testState = TestState.Idle },
                    singleLine = true,
                    label = { Text(stringResource(id = R.string.webdav_field_url)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(value = username, onValueChange = { username = it; testState = TestState.Idle }, singleLine = true, label = { Text(stringResource(id = R.string.webdav_field_username)) })
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; testState = TestState.Idle },
                    singleLine = true,
                    label = { Text(stringResource(id = R.string.webdav_field_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                // Cleartext bleibt erlaubt (s. network_security_config.xml-Kommentar — viele
                // selbst gehostete Server im Heimnetz haben kein gültiges TLS-Zertifikat), aber
                // ein "http://"-Server wird nicht mehr stillschweigend akzeptiert: Benutzername
                // und Passwort gehen bei jedem Request im Klartext übers Netz.
                if (baseUrl.isNotBlank() && !baseUrl.startsWith("https://", ignoreCase = true)) {
                    Text(
                        stringResource(id = R.string.webdav_cleartext_warning),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                TextButton(
                    enabled = isValid && testState != TestState.Running,
                    onClick = {
                        testState = TestState.Running
                        val candidate = WebDavAccount(
                            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                            label = label,
                            baseUrl = baseUrl.trimEnd('/'),
                            username = username,
                            password = password,
                        )
                        scope.launch {
                            testState = WebDavClient.testConnection(candidate).fold(
                                onSuccess = { TestState.Success },
                                onFailure = { error -> TestState.Failed(error.message ?: connectionFailedMessage) },
                            )
                        }
                    },
                ) { Text(stringResource(id = R.string.webdav_test_connection)) }
                when (val state = testState) {
                    TestState.Running -> CircularProgressIndicator(modifier = Modifier.padding(top = 4.dp))
                    TestState.Success -> Text(stringResource(id = R.string.webdav_test_success))
                    is TestState.Failed -> Text(state.message)
                    TestState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid,
                onClick = {
                    onSave(
                        WebDavAccount(
                            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                            label = label,
                            baseUrl = baseUrl.trimEnd('/'),
                            username = username,
                            password = password,
                        ),
                    )
                },
            ) { Text(stringResource(id = R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.action_cancel)) }
        },
    )
}

@Composable
fun WebDavConfirmDeleteDialog(entry: WebDavEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.action_delete)) },
        text = { Text(stringResource(id = R.string.webdav_delete_message, entry.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(id = R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.action_cancel)) }
        },
    )
}

/** Server-Verwaltung von der Home-Übersicht aus — Bearbeiten (öffnet [WebDavAccountDialog]
 * vorbelegt) oder Entfernen des gespeicherten Servers samt Zugangsdaten. */
@Composable
fun WebDavConfirmRemoveAccountDialog(account: WebDavAccount, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.webdav_remove_account_title)) },
        text = { Text(stringResource(id = R.string.webdav_remove_account_message, account.label)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(id = R.string.action_remove)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.action_cancel)) }
        },
    )
}
