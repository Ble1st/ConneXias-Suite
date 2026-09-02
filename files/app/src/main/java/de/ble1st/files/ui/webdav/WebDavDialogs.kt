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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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

    val isValid = label.isNotBlank() && baseUrl.isNotBlank() && username.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Server hinzufügen" else "Server bearbeiten") },
        text = {
            Column {
                OutlinedTextField(value = label, onValueChange = { label = it }, singleLine = true, label = { Text("Bezeichnung") })
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; testState = TestState.Idle },
                    singleLine = true,
                    label = { Text("Server-URL") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(value = username, onValueChange = { username = it; testState = TestState.Idle }, singleLine = true, label = { Text("Benutzername") })
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; testState = TestState.Idle },
                    singleLine = true,
                    label = { Text("Passwort") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                // Cleartext bleibt erlaubt (s. network_security_config.xml-Kommentar — viele
                // selbst gehostete Server im Heimnetz haben kein gültiges TLS-Zertifikat), aber
                // ein "http://"-Server wird nicht mehr stillschweigend akzeptiert: Benutzername
                // und Passwort gehen bei jedem Request im Klartext übers Netz.
                if (baseUrl.isNotBlank() && !baseUrl.startsWith("https://", ignoreCase = true)) {
                    Text(
                        "Achtung: kein HTTPS — Zugangsdaten werden unverschlüsselt übertragen.",
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
                                onFailure = { error -> TestState.Failed(error.message ?: "Verbindung fehlgeschlagen") },
                            )
                        }
                    },
                ) { Text("Verbindung testen") }
                when (val state = testState) {
                    TestState.Running -> CircularProgressIndicator(modifier = Modifier.padding(top = 4.dp))
                    TestState.Success -> Text("Verbindung erfolgreich")
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
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun WebDavConfirmDeleteDialog(entry: WebDavEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Löschen") },
        text = { Text("„${entry.name}“ auf dem Server endgültig löschen?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Löschen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Server-Verwaltung von der Home-Übersicht aus — Bearbeiten (öffnet [WebDavAccountDialog]
 * vorbelegt) oder Entfernen des gespeicherten Servers samt Zugangsdaten. */
@Composable
fun WebDavConfirmRemoveAccountDialog(account: WebDavAccount, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Server entfernen") },
        text = { Text("„${account.label}“ und die gespeicherten Zugangsdaten entfernen? Dateien auf dem Server bleiben unberührt.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Entfernen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
