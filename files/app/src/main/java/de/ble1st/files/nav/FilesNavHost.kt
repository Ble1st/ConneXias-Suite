package de.ble1st.files.nav

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.ble1st.files.data.fs.FileEntry
import de.ble1st.files.data.recent.RecentFilesStore
import de.ble1st.files.data.share.IncomingView
import de.ble1st.files.data.share.PickRequest
import de.ble1st.files.data.webdav.WebDavAccountStore
import de.ble1st.files.permission.StoragePermission
import de.ble1st.files.ui.browser.FileBrowserScreen
import de.ble1st.files.ui.home.HomeScreen
import de.ble1st.files.ui.onboarding.StoragePermissionScreen
import de.ble1st.files.ui.trash.TrashScreen
import de.ble1st.files.ui.viewer.ImageViewerScreen
import de.ble1st.files.ui.viewer.TextEditorScreen
import de.ble1st.files.ui.viewer.VideoPlayerScreen
import de.ble1st.files.ui.viewer.ViewerCategory
import de.ble1st.files.ui.webdav.WebDavBrowserScreen
import de.ble1st.files.util.FileActions
import de.ble1st.files.util.FileCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FilesNavHost(onPicked: (Uri) -> Unit = {}) {
    val navController = rememberNavController()
    val context = LocalContext.current

    NavHost(navController = navController, startDestination = Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            val lifecycleOwner = LocalLifecycleOwner.current
            var hasAccess by remember { mutableStateOf(StoragePermission.hasFullAccess(context)) }
            val legacyPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted -> hasAccess = granted }

            // MANAGE_EXTERNAL_STORAGE (API 30+) wird nicht über einen ActivityResult-Contract
            // gewährt, sondern über einen normalen Settings-Bildschirm, den der Nutzer mit "Zurück"
            // verlässt — deshalb Re-Check bei jedem ON_RESUME statt auf ein Activity-Ergebnis zu
            // warten, das es hier gar nicht gibt.
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        hasAccess = StoragePermission.hasFullAccess(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            LaunchedEffect(hasAccess) {
                if (hasAccess) {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            }

            if (!hasAccess) {
                StoragePermissionScreen(
                    onRequestAccess = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            context.startActivity(StoragePermission.manageAllFilesIntent(context))
                        } else {
                            legacyPermissionLauncher.launch(StoragePermission.legacyPermission)
                        }
                    },
                )
            }
        }

        composable(Routes.HOME) {
            // POST_NOTIFICATIONS ist im Manifest deklariert (für FileOperationService' Foreground-
            // Notification), wurde aber nie zur Laufzeit angefragt — ab API 33 blieb die
            // Fortschritts-/Abbrechen-Notification eines Kopier-/Zip-Jobs dadurch unsichtbar, ohne
            // dass die App das je bemerkt hätte (der Service läuft auch ohne die Berechtigung
            // weiter, nur ohne sichtbare Notification). Einmaliger, nicht-blockierender Request
            // beim ersten Erreichen von Home — ein Ablehnen verhindert keine Dateioperation.
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // analyse.md Abschnitt 5 ("Files ohne ACTION_VIEW"): sobald Speicherzugriff besteht
            // (HOME wird erst danach erreicht) und eine externe Uri wartet, wird sie in einen
            // frischen Cache-Ordner kopiert und dieser wie ein ganz normaler Ordner geöffnet — s.
            // IncomingView-Klassendoc, warum kein eigener Betrachter-Pfad nötig ist.
            var externalViewLoading by remember { mutableStateOf(false) }
            val pendingView by IncomingView.pending.collectAsState()
            LaunchedEffect(pendingView) {
                val uri = pendingView ?: return@LaunchedEffect
                IncomingView.consume()
                externalViewLoading = true
                val dir = withContext(Dispatchers.IO) { IncomingView.copyToCache(context, uri) }
                externalViewLoading = false
                if (dir != null) {
                    navController.navigate(Routes.browser(dir.path))
                } else {
                    Toast.makeText(context, "Datei konnte nicht geöffnet werden", Toast.LENGTH_LONG).show()
                }
            }

            if (externalViewLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                HomeScreen(
                    onOpenFolder = { file -> navController.navigate(Routes.browser(file.path)) },
                    onOpenWebDavAccount = { account -> navController.navigate(Routes.webdav(account.id, "/")) },
                    onOpenTrash = { navController.navigate(Routes.TRASH) },
                    onOpenRecentFile = { entry -> handleFileOpen(navController, context, entry) },
                )
            }
        }

        composable(Routes.TRASH) {
            TrashScreen(onNavigateUp = { navController.popBackStack() })
        }

        composable(
            route = Routes.browserPattern(),
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("path").orEmpty()
            val directory = File(Routes.decodePathArg(encodedPath))
            val pickMode by PickRequest.active.collectAsState()
            FileBrowserScreen(
                directory = directory,
                pickMode = pickMode,
                canNavigateUp = navController.previousBackStackEntry != null,
                onNavigateUp = { navController.popBackStack() },
                onOpenFolder = { file -> navController.navigate(Routes.browser(file.path)) },
                onOpenFile = { entry: FileEntry ->
                    // analyse.md Abschnitt 5 ("Files ist kein Datei-Picker für andere Apps"): im
                    // Auswahlmodus (ACTION_GET_CONTENT) gibt ein Tap die Datei an den Aufrufer
                    // zurück, statt sie im eigenen Betrachter zu öffnen — s. PickRequest-Klassendoc.
                    if (pickMode) {
                        onPicked(FileActions.uriFor(context, entry.file))
                    } else {
                        handleFileOpen(navController, context, entry)
                    }
                },
            )
        }

        composable(
            route = Routes.viewerPattern(),
            arguments = listOf(navArgument("path") { type = NavType.StringType }),
        ) { backStackEntry ->
            val category = ViewerCategory.valueOf(backStackEntry.arguments?.getString("category") ?: "TEXT")
            val encodedPath = backStackEntry.arguments?.getString("path").orEmpty()
            val file = File(Routes.decodePathArg(encodedPath))
            val onBack: () -> Unit = { navController.popBackStack() }
            when (category) {
                ViewerCategory.IMAGE -> ImageViewerScreen(file = file, onBack = onBack)
                ViewerCategory.VIDEO -> VideoPlayerScreen(file = file, onBack = onBack)
                ViewerCategory.TEXT -> TextEditorScreen(file = file, onBack = onBack)
            }
        }

        composable(
            route = Routes.webdavPattern(),
            arguments = listOf(
                navArgument("accountId") { type = NavType.StringType },
                navArgument("path") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString("accountId").orEmpty()
            val account = WebDavAccountStore.list(context).find { it.id == accountId }
            val encodedPath = backStackEntry.arguments?.getString("path").orEmpty()
            val path = Routes.decodePathArg(encodedPath)
            if (account == null) {
                // Server wurde zwischenzeitlich entfernt (z. B. über Home) — kein Absturz auf
                // einen jetzt ungültigen Account-Verweis, einfach zurück.
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                WebDavBrowserScreen(
                    account = account,
                    path = path,
                    onNavigateUp = { navController.popBackStack() },
                    onOpenFolder = { newPath -> navController.navigate(Routes.webdav(account.id, newPath)) },
                    onOpenViewer = { file, category -> navController.navigate(Routes.viewer(category.name, file.path)) },
                )
            }
        }
    }
}

private fun handleFileOpen(
    navController: androidx.navigation.NavController,
    context: android.content.Context,
    entry: FileEntry,
) {
    val category = when (entry.category) {
        FileCategory.IMAGE -> ViewerCategory.IMAGE
        FileCategory.VIDEO -> ViewerCategory.VIDEO
        FileCategory.TEXT -> ViewerCategory.TEXT
        else -> null
    }
    // "Zuletzt verwendet" auf Home (s. RecentFilesStore-Klassendoc) protokolliert an genau dieser
    // einen Stelle, durch die jeder tatsächliche Datei-Öffnen-Tap aus dem Browser läuft — nicht in
    // FileBrowserScreen selbst, sonst müsste dieselbe Logik zusätzlich für den Home-Eintragspunkt
    // unten dupliziert werden.
    RecentFilesStore.recordOpened(context, entry.file)
    if (category != null) {
        navController.navigate(Routes.viewer(category.name, entry.file.path))
    } else {
        FileActions.openWithOtherApp(context, entry.file)
    }
}
