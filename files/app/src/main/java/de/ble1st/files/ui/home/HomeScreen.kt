package de.ble1st.files.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ble1st.files.R
import de.ble1st.files.data.fs.FileEntry
import de.ble1st.files.data.fs.StorageRoot
import de.ble1st.files.data.fs.StorageRoots
import de.ble1st.files.data.recent.RecentFilesStore
import de.ble1st.files.data.share.IncomingShare
import de.ble1st.files.data.webdav.WebDavAccount
import de.ble1st.files.data.webdav.WebDavAccountStore
import de.ble1st.files.util.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import de.ble1st.files.ui.webdav.WebDavAccountDialog
import de.ble1st.files.ui.webdav.WebDavConfirmRemoveAccountDialog
import java.io.File

private sealed interface HomeWebDavDialog {
    data object Add : HomeWebDavDialog
    data class Edit(val account: WebDavAccount) : HomeWebDavDialog
    data class Remove(val account: WebDavAccount) : HomeWebDavDialog
}

/**
 * Startbildschirm nach erteilter Speicherberechtigung: jedes erkannte Speichervolume (intern +
 * SD-Karten) plus direkte Verknüpfungen auf die üblichen Unterordner des internen Speichers.
 * Bewusst kein rekursiver Index/keine Suche über den gesamten Speicher hier — reine
 * Einstiegspunkte in [de.ble1st.files.ui.browser.FileBrowserScreen].
 */
@Composable
fun HomeScreen(
    onOpenFolder: (File) -> Unit,
    onOpenWebDavAccount: (WebDavAccount) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenRecentFile: (FileEntry) -> Unit,
) {
    val context = LocalContext.current
    val storageRoots = remember { StorageRoots.list(context) }
    val primaryRoot = storageRoots.firstOrNull()?.path
    val quickAccess = remember(primaryRoot) {
        primaryRoot?.let { StorageRoots.quickAccessFolders(it) } ?: emptyList()
    }
    // WebDavAccountStore lädt lazy beim ersten list()-Aufruf; danach hält der StateFlow den
    // Home-Bildschirm auch nach Hinzufügen/Entfernen eines Servers automatisch aktuell.
    LaunchedEffect(Unit) { WebDavAccountStore.list(context) }
    val webDavAccounts by WebDavAccountStore.accounts.collectAsState()
    var dialog by remember { mutableStateOf<HomeWebDavDialog?>(null) }
    val pendingShare by IncomingShare.pending.collectAsState()

    // RecentFilesStore hält nur Pfad+Zeitpunkt (s. dortiges Klassendoc) — die eigentlichen
    // FileEntry-Objekte (Größe, Icon-Kategorie) werden hier auf Dispatchers.IO frisch aufgelöst,
    // damit eine zwischenzeitlich gelöschte/verschobene Datei still aus der Liste fällt statt mit
    // veralteten Metadaten oder einem Absturz aufzutauchen.
    LaunchedEffect(Unit) { RecentFilesStore.list(context) }
    val rawRecent by RecentFilesStore.entries.collectAsState()
    var recentFileEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    LaunchedEffect(rawRecent) {
        recentFileEntries = withContext(Dispatchers.IO) {
            rawRecent.mapNotNull { recent ->
                val file = File(recent.path)
                if (file.isFile) FileEntry.from(file) else null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenTrash) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(id = R.string.trash_title))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            pendingShare?.let { uris ->
                item {
                    // Konsumiert wird die Liste erst in FileBrowserScreen (sobald der Nutzer
                    // tatsächlich einen Zielordner öffnet) — hier nur ein Hinweis, damit das
                    // "Teilen"-Ergebnis nicht spurlos verschwindet, ohne dass klar ist, wo es
                    // gelandet ist.
                    ListItem(
                        headlineContent = { Text("${uris.size} geteilte Datei(en) empfangen") },
                        supportingContent = { Text("Ordner öffnen, um sie dort zu speichern") },
                        leadingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (recentFileEntries.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(id = R.string.home_section_recent),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(recentFileEntries, key = { "recent_" + it.file.path }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = { Text(formatFileSize(entry.sizeBytes)) },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRecentFile(entry) },
                    )
                }
            }
            item {
                Text(
                    text = stringResource(id = R.string.home_section_storage),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(storageRoots) { root: StorageRoot ->
                ListItem(
                    headlineContent = { Text(root.label) },
                    supportingContent = { Text(root.path.path) },
                    leadingContent = { Icon(storageIcon(root.label), contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenFolder(root.path) },
                )
            }
            item {
                Text(
                    text = stringResource(id = R.string.home_section_quick_access),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(quickAccess) { folder ->
                ListItem(
                    headlineContent = { Text(folder.label) },
                    leadingContent = { Icon(quickAccessIcon(folder.label), contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenFolder(folder.path) },
                )
            }
            item {
                Text(
                    text = "Netzwerkspeicher",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(webDavAccounts, key = { it.id }) { account ->
                var menuExpanded by remember(account.id) { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(account.label) },
                    supportingContent = { Text(account.baseUrl) },
                    leadingContent = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Mehr")
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("Bearbeiten") },
                                    onClick = { menuExpanded = false; dialog = HomeWebDavDialog.Edit(account) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Entfernen") },
                                    onClick = { menuExpanded = false; dialog = HomeWebDavDialog.Remove(account) },
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenWebDavAccount(account) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Server hinzufügen") },
                    leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dialog = HomeWebDavDialog.Add },
                )
            }
        }
    }

    when (val current = dialog) {
        HomeWebDavDialog.Add -> WebDavAccountDialog(
            existing = null,
            onSave = { account -> WebDavAccountStore.upsert(context, account); dialog = null },
            onDismiss = { dialog = null },
        )
        is HomeWebDavDialog.Edit -> WebDavAccountDialog(
            existing = current.account,
            onSave = { account -> WebDavAccountStore.upsert(context, account); dialog = null },
            onDismiss = { dialog = null },
        )
        is HomeWebDavDialog.Remove -> WebDavConfirmRemoveAccountDialog(
            account = current.account,
            onConfirm = { WebDavAccountStore.remove(context, current.account.id); dialog = null },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private fun storageIcon(label: String): ImageVector =
    if (label.startsWith("SD")) Icons.Filled.SdCard else Icons.Filled.Smartphone

private fun quickAccessIcon(label: String): ImageVector = when (label) {
    "Downloads" -> Icons.Filled.Download
    "Bilder" -> Icons.Filled.Image
    "DCIM" -> Icons.Filled.PhotoCamera
    "Videos" -> Icons.Filled.VideoLibrary
    "Audio" -> Icons.Filled.MusicNote
    "Dokumente" -> Icons.AutoMirrored.Filled.InsertDriveFile
    else -> Icons.Filled.Folder
}
