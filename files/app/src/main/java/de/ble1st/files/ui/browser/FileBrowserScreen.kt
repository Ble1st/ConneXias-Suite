package de.ble1st.files.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ble1st.files.R
import de.ble1st.files.data.fileops.ClipboardHolder
import de.ble1st.files.data.fileops.ClipboardMode
import de.ble1st.files.data.fileops.FileOperationQueue
import de.ble1st.files.data.fileops.OperationState
import de.ble1st.files.data.fs.FileEntry
import de.ble1st.files.data.share.IncomingShare
import de.ble1st.files.data.share.PickSpec
import de.ble1st.files.data.fs.SortKey
import de.ble1st.files.data.fs.SortOrder
import de.ble1st.files.data.fs.StorageRoot
import de.ble1st.files.data.fs.StorageRoots
import de.ble1st.files.data.search.RecursiveSearch
import de.ble1st.files.util.FileActions
import de.ble1st.files.util.FileCategory
import java.io.File

@Composable
fun FileBrowserScreen(
    directory: File,
    canNavigateUp: Boolean,
    onNavigateUp: () -> Unit,
    onOpenFolder: (File) -> Unit,
    onOpenFile: (FileEntry) -> Unit,
    onOpenLocalShare: (File) -> Unit,
    // analyse.md Abschnitt 5 ("Files ist kein Datei-Picker für andere Apps"): != null, während
    // diese Activity-Instanz eine ACTION_GET_CONTENT-Anfrage einer Fremd-App bedient (s.
    // data/share/PickRequest.kt). Trägt seit 2026-09-03 auch den angefragten Typ-Filter und das
    // Mehrfachauswahl-Flag — der eigentliche Tap-Dispatch für die Einzelauswahl liegt weiterhin
    // beim Aufrufer in FilesNavHost.
    pickSpec: PickSpec? = null,
    /** Übergibt die im Mehrfachauswahl-Modus bestätigte Auswahl an den Aufrufer. Bei
     * Einzelauswahl unbenutzt (die läuft weiterhin über `onOpenFile`). */
    onPickMultiple: (List<File>) -> Unit = {},
) {
    val context = LocalContext.current
    // LocalResources statt LocalContext.current.resources: die Snackbar-Texte weiter unten
    // entstehen in Coroutinen, und ein über LocalContext gezogenes Resources-Objekt bekommt eine
    // Konfigurationsänderung (Sprache, Dunkelmodus, Größe) nicht mit — lint meldet das als Fehler.
    val resources = LocalResources.current
    val application = context.applicationContext as android.app.Application
    val viewModel: FileBrowserViewModel = viewModel(
        key = directory.path,
        factory = FileBrowserViewModel.Factory(application, directory),
    )
    val state by viewModel.uiState.collectAsState()
    val clipboard by viewModel.clipboard.collectAsState()
    val pickMode = pickSpec != null
    // Der Typ-Filter muss im ViewModel-Zustand landen, nicht nur an der Anzeigestelle greifen —
    // sonst würde "Alles auswählen" auch die ausgefilterten Dateien mit auswählen (s.
    // setPickMimeTypes-Doc).
    LaunchedEffect(pickSpec) {
        viewModel.setPickMimeTypes(if (pickSpec?.hasTypeRestriction == true) pickSpec.mimeTypes else emptyList())
    }
    val operationState by FileOperationQueue.state.collectAsState()
    // Seit der Service eine echte Warteschlange hat (s. FileOperationService-Klassendoc), sperrt
    // die UI keine Aktionen mehr, solange ein Job läuft — ein weiterer Auftrag reiht sich
    // einfach ein. Angezeigt wird nur noch, wie viele warten.
    val queuedCount by FileOperationQueue.pendingCount.collectAsState()
    val operationResults by FileOperationQueue.results.collectAsState()
    var searchActive by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var addMenuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importUris(uris)
    }
    // File("/storage/emulated/0").name liefert nur "0" (letztes Pfadsegment) — für einen
    // Speicher-Root (von HomeScreen aus geöffnet) zeigt der Titel deshalb das dort schon bekannte
    // sprechende Label statt dieser Zahl (live am Gerät als "0" im Titel aufgefallen).
    val storageRoot = remember(directory) {
        StorageRoots.list(context).firstOrNull { it.path == directory }
    }
    val storageRootLabel = storageRoot?.let {
        when (it.kind) {
            StorageRoot.Kind.INTERNAL -> stringResource(id = R.string.storage_internal)
            StorageRoot.Kind.REMOVABLE -> stringResource(id = R.string.storage_removable, it.ordinal)
        }
    }
    val screenTitle = storageRootLabel ?: directory.name.ifEmpty { directory.path }
    val effectiveTitle = if (pickMode) {
        stringResource(id = R.string.browser_pick_title, screenTitle)
    } else {
        screenTitle
    }

    val pendingShare by IncomingShare.pending.collectAsState()
    // Sobald der Nutzer nach einem "Teilen mit ConneXias Files" (s. IncomingShare-Klassendoc)
    // irgendeinen Ordner öffnet, gilt das als Zielordner-Wahl — dieselbe Kopierlogik wie beim
    // SAF-Import (importUris), nur mit den vom System gelieferten Uris statt einem Picker-Ergebnis.
    LaunchedEffect(pendingShare) {
        if (pendingShare == null) return@LaunchedEffect
        val uris = IncomingShare.consume()
        if (uris.isNotEmpty()) {
            viewModel.importUris(uris)
            snackbarHostState.showSnackbar(resources.getString(R.string.browser_import_started))
        }
    }

    // Sobald ein Kopier-/Verschiebe-/Lösch-/Zip-Job im Hintergrund fertig ist, muss die Liste neu
    // geladen werden — der Service läuft außerhalb des ViewModels und kennt die aktuell sichtbaren
    // Ordner nicht, ein einfacher Refresh bei jedem Completed ist günstiger als gezieltes Tracking.
    // Ausgelöst wird auf der id des ältesten unquittierten Ergebnisses, nicht auf der Liste
    // selbst: sonst würde ein währenddessen fertig werdender zweiter Auftrag die gerade laufende
    // Snackbar-Anzeige des ersten abbrechen und von vorn beginnen lassen.
    val nextResult = operationResults.firstOrNull()
    LaunchedEffect(nextResult?.id) {
        val result = nextResult ?: return@LaunchedEffect
        when (val s = result.state) {
            is OperationState.Completed -> {
                viewModel.refresh()
                // Die Ergebnis-Snackbars entstehen in einer Coroutine, nicht in einem
                // Composable-Aufruf — deshalb durchgehend resources.getString()/
                // getQuantityString() statt stringResource().
                val parts = buildList {
                    add(resources.getString(R.string.browser_op_done, s.successCount))
                    if (s.skippedCount > 0) {
                        add(
                            resources.getQuantityString(
                                R.plurals.browser_op_skipped,
                                s.skippedCount,
                                s.skippedCount,
                            ),
                        )
                    }
                    if (s.failedCount > 0) {
                        add(
                            resources.getQuantityString(
                                R.plurals.browser_op_failed_count,
                                s.failedCount,
                                s.failedCount,
                            ),
                        )
                    }
                }
                snackbarHostState.showSnackbar(parts.joinToString(", "))
            }
            is OperationState.Failed ->
                snackbarHostState.showSnackbar(
                    resources.getString(R.string.browser_op_failed, s.message),
                )
            is OperationState.Cancelled -> {
                viewModel.refresh()
                snackbarHostState.showSnackbar(
                    if (s.droppedCount > 0) {
                        resources.getQuantityString(
                            R.plurals.browser_op_cancelled_dropped,
                            s.droppedCount,
                            s.droppedCount,
                        )
                    } else {
                        resources.getString(R.string.browser_op_cancelled)
                    },
                )
            }
            else -> Unit
        }
        FileOperationQueue.acknowledgeResult(result)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                if (state.isSelectionMode) {
                    SelectionTopBar(
                        // Im Mehrfachauswahl-Modus einer fremden App ersetzt ein Bestätigen-Haken
                        // die sonstigen Aktionen: Kopieren/Löschen/Komprimieren wären dort nicht
                        // nur nutzlos, sondern verwirrend — der Aufrufer wartet auf Dateien.
                        onConfirmPick = if (pickSpec?.allowMultiple == true) {
                            { onPickMultiple(viewModel.selectedEntries().map { it.file }) }
                        } else {
                            null
                        },
                        selectedCount = state.selectedPaths.size,
                        onClose = viewModel::clearSelection,
                        onSelectAll = viewModel::selectAll,
                        onCopy = viewModel::copySelectionToClipboard,
                        onCut = viewModel::cutSelectionToClipboard,
                        onDelete = { viewModel.showDialog(BrowserDialog.ConfirmDelete(viewModel.selectedEntries())) },
                        onShare = { FileActions.share(context, viewModel.selectedEntries().map { it.file }) },
                        onCompress = { viewModel.showDialog(BrowserDialog.Compress(viewModel.selectedEntries())) },
                    )
                } else if (searchActive) {
                    SearchTopBar(
                        query = state.searchQuery,
                        recursive = state.recursiveSearch,
                        onQueryChange = viewModel::setSearchQuery,
                        onToggleRecursive = { viewModel.setRecursiveSearch(!state.recursiveSearch) },
                        onClose = {
                            searchActive = false
                            viewModel.setSearchQuery("")
                        },
                    )
                } else {
                    TopAppBar(
                        title = { Text(effectiveTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            if (canNavigateUp) {
                                IconButton(onClick = onNavigateUp) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(id = R.string.content_desc_back),
                                    )
                                }
                            }
                        },
                        actions = {
                            // Im Auswahlmodus (ACTION_GET_CONTENT, s. pickMode-Doc oben) macht eine
                            // Netzwerkfreigabe keinen Sinn — die Fremd-App wartet auf eine
                            // zurückgegebene Datei, nicht auf einen geteilten Ordner.
                            if (!pickMode) {
                                IconButton(onClick = { onOpenLocalShare(directory) }) {
                                    Icon(Icons.Filled.WifiTethering, contentDescription = stringResource(id = R.string.local_share_title))
                                }
                            }
                            IconButton(onClick = { searchActive = true }) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = stringResource(id = R.string.content_desc_search),
                                )
                            }
                            val nextMode = if (state.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
                            IconButton(onClick = { viewModel.setViewMode(nextMode) }) {
                                Icon(
                                    imageVector = if (state.viewMode == ViewMode.LIST) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                                    contentDescription = stringResource(id = R.string.content_desc_toggle_view),
                                )
                            }
                            Box {
                                IconButton(onClick = { sortMenuExpanded = true }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Sort,
                                        contentDescription = stringResource(id = R.string.content_desc_sort),
                                    )
                                }
                                SortMenu(
                                    expanded = sortMenuExpanded,
                                    current = state.sortOrder,
                                    onDismiss = { sortMenuExpanded = false },
                                    onSelect = { viewModel.setSortOrder(it); sortMenuExpanded = false },
                                )
                            }
                        },
                    )
                }
                if (operationState is OperationState.Running) {
                    val progress = (operationState as OperationState.Running).progress
                    val fraction = if (progress.totalCount > 0) progress.processedCount / progress.totalCount.toFloat() else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    if (queuedCount > 0) {
                        Text(
                            text = pluralStringResource(id = R.plurals.operation_queue_pending, count = queuedCount, queuedCount),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    }
                } else if (state.searchRunning) {
                    // Unbestimmter Balken: die rekursive Suche kennt die Gesamtzahl der zu
                    // durchlaufenden Ordner nicht im Voraus und könnte deshalb keinen ehrlichen
                    // Fortschritt anzeigen.
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        floatingActionButton = {
            // Die Einfügen-Leiste unten (PasteBar) sitzt an derselben Ecke wie dieses FAB und
            // überlappte es live am Gerät ("Einfügen" halb vom FAB verdeckt) — Scaffold reserviert
            // dafür keinen Platz automatisch. Solange etwas in der Zwischenablage liegt, ersetzt
            // die PasteBar das FAB als primäre Aktion in dieser Ecke.
            if (clipboard == null) Box {
                FloatingActionButton(onClick = { addMenuExpanded = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(id = R.string.action_new))
                }
                DropdownMenu(expanded = addMenuExpanded, onDismissRequest = { addMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(id = R.string.action_new_folder)) },
                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                        onClick = { addMenuExpanded = false; viewModel.showDialog(BrowserDialog.NewFolder) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(id = R.string.action_new_file)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null) },
                        onClick = { addMenuExpanded = false; viewModel.showDialog(BrowserDialog.NewFile) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(id = R.string.action_import)) },
                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        onClick = {
                            addMenuExpanded = false
                            importLauncher.launch(arrayOf("*/*"))
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoading -> Unit
                    state.errorMessage != null -> Text(
                        text = state.errorMessage ?: "",
                        modifier = Modifier.padding(16.dp),
                    )
                    state.visibleEntries.isEmpty() -> Text(
                        text = if (state.showingSearchResults && !state.searchRunning) {
                            stringResource(id = R.string.search_no_results)
                        } else {
                            stringResource(id = R.string.browser_empty)
                        },
                        modifier = Modifier.padding(16.dp),
                    )
                    state.viewMode == ViewMode.GRID -> FileEntryGrid(
                        entries = state.visibleEntries,
                        selectedPaths = state.selectedPaths,
                        isSelectionMode = state.isSelectionMode,
                        onClick = { entry ->
                            when {
                                state.isSelectionMode -> viewModel.toggleSelection(entry)
                                entry.isDirectory -> onOpenFolder(entry.file)
                                // Mehrfachauswahl (EXTRA_ALLOW_MULTIPLE): ein Tap wählt aus,
                                // statt die Datei sofort zurückzugeben und die Activity zu
                                // beenden — sonst käme man nie über eine Datei hinaus.
                                pickSpec?.allowMultiple == true -> viewModel.toggleSelection(entry)
                                else -> onOpenFile(entry)
                            }
                        },
                        onLongClick = { entry -> viewModel.toggleSelection(entry) },
                        pathLabelFor = { entry ->
                            if (state.showingSearchResults) viewModel.relativeParentPathOf(entry) else null
                        },
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.visibleEntries, key = { it.file.path }) { entry ->
                            var rowMenuExpanded by remember(entry.file.path) { mutableStateOf(false) }
                            FileEntryRow(
                                entry = entry,
                                isSelected = entry.file.path in state.selectedPaths,
                                isSelectionMode = state.isSelectionMode,
                                onClick = {
                                    when {
                                        state.isSelectionMode -> viewModel.toggleSelection(entry)
                                        entry.isDirectory -> onOpenFolder(entry.file)
                                        // s. Kommentar im Grid-Zweig oben
                                        pickSpec?.allowMultiple == true -> viewModel.toggleSelection(entry)
                                        else -> onOpenFile(entry)
                                    }
                                },
                                onLongClick = { viewModel.toggleSelection(entry) },
                                pathLabel = if (state.showingSearchResults) {
                                    viewModel.relativeParentPathOf(entry)
                                } else {
                                    null
                                },
                                trailingContent = {
                                    Box {
                                        IconButton(onClick = { rowMenuExpanded = true }) {
                                            Icon(
                                                Icons.Filled.MoreVert,
                                                contentDescription = stringResource(id = R.string.content_desc_more),
                                            )
                                        }
                                        RowActionsMenu(
                                            expanded = rowMenuExpanded,
                                            entry = entry,
                                            onDismiss = { rowMenuExpanded = false },
                                            onRename = { viewModel.showDialog(BrowserDialog.Rename(entry)) },
                                            onDelete = { viewModel.showDialog(BrowserDialog.ConfirmDelete(listOf(entry))) },
                                            onCopy = { viewModel.toggleSelection(entry); viewModel.copySelectionToClipboard() },
                                            onCut = { viewModel.toggleSelection(entry); viewModel.cutSelectionToClipboard() },
                                            onShare = { FileActions.share(context, listOf(entry.file)) },
                                            onProperties = { viewModel.showDialog(BrowserDialog.Properties(entry)) },
                                            onExtract = { viewModel.extractHere(entry) },
                                                            )
                                    }
                                },
                            )
                        }
                    }
                }
            }
            // Ehrlicher Hinweis statt einer scheinbar vollständigen Liste: die Suche bricht bei
            // 500 Treffern bzw. 20 000 durchlaufenen Ordnern ab (s. RecursiveSearch-Klassendoc).
            if (state.showingSearchResults && state.searchTruncated) {
                Text(
                    text = pluralStringResource(
                        R.plurals.search_truncated,
                        RecursiveSearch.MAX_RESULTS,
                        RecursiveSearch.MAX_RESULTS,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (clipboard != null) {
                PasteBar(
                    isCut = clipboard?.mode == ClipboardMode.CUT,
                    count = clipboard?.paths?.size ?: 0,
                    onPaste = viewModel::pasteFromClipboard,
                    onCancel = { ClipboardHolder.clear() },
                )
            }
        }
    }

    when (val dialog = state.pendingDialog) {
        is BrowserDialog.NewFolder -> NameInputDialog(
            title = stringResource(id = R.string.action_new_folder),
            initialValue = "",
            confirmLabel = stringResource(id = R.string.action_confirm),
            onConfirm = { viewModel.createFolder(it) },
            onDismiss = { viewModel.showDialog(null) },
        )
        is BrowserDialog.NewFile -> NameInputDialog(
            title = stringResource(id = R.string.action_new_file),
            initialValue = "",
            confirmLabel = stringResource(id = R.string.action_confirm),
            onConfirm = { viewModel.createFile(it) },
            onDismiss = { viewModel.showDialog(null) },
        )
        is BrowserDialog.Rename -> NameInputDialog(
            title = stringResource(id = R.string.action_rename),
            initialValue = dialog.entry.name,
            confirmLabel = stringResource(id = R.string.action_confirm),
            onConfirm = { viewModel.rename(dialog.entry, it) },
            onDismiss = { viewModel.showDialog(null) },
        )
        is BrowserDialog.ConfirmDelete -> ConfirmDeleteDialog(
            entries = dialog.entries,
            onConfirm = { viewModel.deleteEntries(dialog.entries) },
            onDismiss = { viewModel.showDialog(null) },
        )
        is BrowserDialog.Properties -> PropertiesDialog(entry = dialog.entry, onDismiss = { viewModel.showDialog(null) })
        is BrowserDialog.Compress -> NameInputDialog(
            title = stringResource(id = R.string.action_compress),
            initialValue = (dialog.entries.singleOrNull()?.name
                ?: stringResource(id = R.string.default_archive_name)) + ".zip",
            confirmLabel = stringResource(id = R.string.action_confirm),
            onConfirm = { viewModel.compress(dialog.entries, it) },
            onDismiss = { viewModel.showDialog(null) },
        )
        is BrowserDialog.ConflictResolution -> ConflictResolutionDialog(
            conflictingNames = dialog.conflictingNames,
            onResolve = { viewModel.resolveConflictAndPaste(it) },
            onDismiss = { viewModel.resolveConflictAndPaste(null) },
        )
        null -> Unit
    }
}

@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    onConfirmPick: (() -> Unit)?,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onCompress: () -> Unit,
) {
    // Sechs Action-Icons + Schließen-Icon lassen auf einem schmalen Telefon (~360dp) keinen Platz
    // mehr für den Titeltext ("1 ausgewählt" bricht sonst Zeichen für Zeichen um, live am Gerät
    // beobachtet) — deshalb nur die vier häufigsten Aktionen direkt, der Rest hinter einem
    // Overflow-Menü, plus explizites maxLines/Ellipsis als zweite Absicherung.
    var overflowExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Text(
                pluralStringResource(R.plurals.browser_selected_count, selectedCount, selectedCount),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(id = R.string.content_desc_close_selection),
                )
            }
        },
        actions = {
            if (onConfirmPick != null) {
                IconButton(onClick = onConfirmPick, enabled = selectedCount > 0) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(id = R.string.action_pick_confirm))
                }
            } else {
                IconButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(id = R.string.action_copy))
                }
                IconButton(onClick = onCut) {
                    Icon(Icons.Filled.ContentCut, contentDescription = stringResource(id = R.string.action_cut))
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(id = R.string.action_share))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(id = R.string.action_delete))
                }
                Box {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(id = R.string.content_desc_more),
                        )
                    }
                    DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(id = R.string.action_select_all)) },
                            onClick = { overflowExpanded = false; onSelectAll() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(id = R.string.action_compress)) },
                            onClick = { overflowExpanded = false; onCompress() },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun SearchTopBar(
    query: String,
    recursive: Boolean,
    onQueryChange: (String) -> Unit,
    onToggleRecursive: () -> Unit,
    onClose: () -> Unit,
) {
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = {
                    Text(
                        stringResource(
                            id = if (recursive) R.string.search_placeholder_recursive else R.string.search_placeholder_local,
                        ),
                    )
                },
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(id = R.string.content_desc_close_search),
                )
            }
        },
        actions = {
            // Umschalter zwischen "nur dieser Ordner" (die bisherige, rein lokale Filterung der
            // bereits geladenen Liste) und "auch alle Unterordner" (echter Baumdurchlauf, s.
            // RecursiveSearch). Eingefärbt statt beschriftet, weil in der Titelzeile neben dem
            // Eingabefeld kein Platz für Text ist.
            IconButton(onClick = onToggleRecursive) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ManageSearch,
                    contentDescription = stringResource(id = R.string.search_toggle_recursive),
                    tint = if (recursive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    )
}

@Composable
private fun SortMenu(
    expanded: Boolean,
    current: SortOrder,
    onDismiss: () -> Unit,
    onSelect: (SortOrder) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortKey.entries.forEach { key ->
            DropdownMenuItem(
                text = { Text(sortKeyLabel(key)) },
                onClick = {
                    val ascending = if (current.key == key) !current.ascending else true
                    onSelect(current.copy(key = key, ascending = ascending))
                },
            )
        }
    }
}

@Composable
private fun sortKeyLabel(key: SortKey): String = stringResource(
    id = when (key) {
        SortKey.NAME -> R.string.sort_key_name
        SortKey.DATE -> R.string.sort_key_date
        SortKey.SIZE -> R.string.sort_key_size
        SortKey.TYPE -> R.string.sort_key_type
    },
)

@Composable
private fun RowActionsMenu(
    expanded: Boolean,
    entry: FileEntry,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onShare: () -> Unit,
    onProperties: () -> Unit,
    onExtract: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(id = R.string.action_rename)) },
            onClick = { onDismiss(); onRename() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(id = R.string.action_copy)) },
            onClick = { onDismiss(); onCopy() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(id = R.string.action_cut)) },
            onClick = { onDismiss(); onCut() },
        )
        if (de.ble1st.files.util.isExtractable(entry.file.name)) {
            DropdownMenuItem(
                text = { Text(stringResource(id = R.string.action_extract)) },
                onClick = { onDismiss(); onExtract() },
            )
        }
        if (!entry.isDirectory) {
            DropdownMenuItem(
                text = { Text(stringResource(id = R.string.action_share)) },
                onClick = { onDismiss(); onShare() },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(id = R.string.action_properties)) },
            onClick = { onDismiss(); onProperties() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(id = R.string.action_delete)) },
            onClick = { onDismiss(); onDelete() },
        )
    }
}

@Composable
private fun PasteBar(isCut: Boolean, count: Int, onPaste: () -> Unit, onCancel: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                pluralStringResource(
                    id = if (isCut) R.plurals.clipboard_pending_move else R.plurals.clipboard_pending_copy,
                    count = count,
                    count,
                ),
            )
            Row {
                Button(onClick = onCancel) { Text(stringResource(id = R.string.action_discard)) }
                Button(onClick = onPaste) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Text(" " + stringResource(id = R.string.action_paste))
                }
            }
        }
    }
}
