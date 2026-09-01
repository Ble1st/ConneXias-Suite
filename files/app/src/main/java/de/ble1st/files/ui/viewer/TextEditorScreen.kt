package de.ble1st.files.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.ble1st.files.util.FileActions
import de.ble1st.files.util.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Größer wird nicht mehr in den Editor geladen — eine simple `TextField`-Bearbeitung hält den
 * kompletten Inhalt als String im Speicher, ein Mehrfaches davon während des Tippens (Undo-Stack
 * o. Ä. des IME); 2 MB ist für Klartext-/Code-Dateien im typischen Fall (s. MimeTypeResolver
 * PLAIN_TEXT_EXTENSIONS) großzügig genug, ohne bei einer versehentlich geöffneten Binärdatei OOM
 * zu riskieren. */
private const val MAX_EDITABLE_BYTES = 2L * 1024 * 1024

private enum class TextLoadState { LOADING, LOADED, TOO_LARGE, ERROR }

/**
 * Eigener Texteditor (Nutzeranforderung, löst [PlaceholderViewerScreen] für TEXT ab): Lesen,
 * Bearbeiten, Speichern. Bewusst ohne Syntax-Hervorhebung/Zeilennummern — das wäre ein eigener
 * Ausbauschritt, kein Tag-1-Bedarf für "Datei ansehen und schnell einen Wert ändern".
 */
@Composable
fun TextEditorScreen(file: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var loadState by remember(file) { mutableStateOf(TextLoadState.LOADING) }
    var text by remember(file) { mutableStateOf("") }
    var originalText by remember(file) { mutableStateOf("") }
    var isSaving by remember(file) { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(file) {
        if (file.length() > MAX_EDITABLE_BYTES) {
            loadState = TextLoadState.TOO_LARGE
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) { runCatching { file.readText(Charsets.UTF_8) } }
        result.onSuccess {
            originalText = it
            text = it
            loadState = TextLoadState.LOADED
        }.onFailure {
            loadState = TextLoadState.ERROR
        }
    }

    val isDirty = text != originalText

    fun save() {
        scope.launch {
            isSaving = true
            val result = withContext(Dispatchers.IO) { runCatching { file.writeText(text, Charsets.UTF_8) } }
            isSaving = false
            result.onSuccess {
                originalText = text
                snackbarHostState.showSnackbar("Gespeichert")
            }.onFailure { error ->
                snackbarHostState.showSnackbar("Speichern fehlgeschlagen: ${error.message}")
            }
        }
    }

    val handleBack = { if (isDirty) showDiscardDialog = true else onBack() }
    BackHandler(onBack = handleBack)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    if (loadState == TextLoadState.LOADED) {
                        IconButton(onClick = ::save, enabled = isDirty && !isSaving) {
                            Icon(Icons.Filled.Save, contentDescription = "Speichern")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (loadState) {
            TextLoadState.LOADING -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            TextLoadState.TOO_LARGE -> EditorFallback(
                padding = padding,
                file = file,
                message = "Diese Datei ist größer als ${formatFileSize(MAX_EDITABLE_BYTES)} und zu groß " +
                    "für den eingebauten Texteditor.",
            )

            TextLoadState.ERROR -> EditorFallback(
                padding = padding,
                file = file,
                message = "Datei konnte nicht als Text gelesen werden.",
            )

            TextLoadState.LOADED -> TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxSize().padding(padding),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Änderungen verwerfen?") },
            text = { Text("Es gibt ungespeicherte Änderungen an „${file.name}“.") },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; onBack() }) { Text("Verwerfen") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("Weiter bearbeiten") }
            },
        )
    }
}

@Composable
private fun EditorFallback(padding: androidx.compose.foundation.layout.PaddingValues, file: File, message: String) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = { FileActions.openWithOtherApp(context, file) }) {
            Text("Mit anderer App öffnen")
        }
    }
}
