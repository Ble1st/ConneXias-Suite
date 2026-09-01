package de.ble1st.gallery.nav

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.ble1st.gallery.data.media.ALL_BUCKET_ID
import de.ble1st.gallery.data.media.MediaType
import de.ble1st.gallery.permission.MediaPermission
import de.ble1st.gallery.ui.GalleryViewModel
import de.ble1st.gallery.ui.albums.AlbumsScreen
import de.ble1st.gallery.ui.albums.CustomAlbumScreen
import de.ble1st.gallery.ui.editor.PhotoEditorScreen
import de.ble1st.gallery.ui.grid.MediaGridScreen
import de.ble1st.gallery.ui.onboarding.MediaPermissionScreen
import de.ble1st.gallery.ui.sync.CloudSyncScreen
import de.ble1st.gallery.ui.trash.TrashScreen
import de.ble1st.gallery.ui.viewer.ImageViewerScreen
import de.ble1st.gallery.ui.viewer.SlideshowScreen
import de.ble1st.gallery.ui.viewer.VideoPlayerScreen

@Composable
fun GalleryNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current
    // Ein einziges ViewModel für die gesamte NavHost-Lebensdauer statt eines pro Route — s.
    // GalleryViewModel-Klassendoc.
    val galleryViewModel: GalleryViewModel = viewModel()

    NavHost(navController = navController, startDestination = Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            val lifecycleOwner = LocalLifecycleOwner.current
            var hasAccess by remember { mutableStateOf(MediaPermission.hasAccess(context)) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { results -> hasAccess = results.values.all { it } }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) hasAccess = MediaPermission.hasAccess(context)
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            LaunchedEffect(hasAccess) {
                if (hasAccess) {
                    navController.navigate(Routes.ALBUMS) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            }

            if (!hasAccess) {
                MediaPermissionScreen(onRequestAccess = { permissionLauncher.launch(MediaPermission.required) })
            }
        }

        composable(Routes.ALBUMS) {
            AlbumsScreen(
                viewModel = galleryViewModel,
                onOpenBucket = { bucketId, bucketName -> navController.navigate(Routes.grid(bucketId, bucketName)) },
                onOpenCustomAlbum = { albumId, albumName -> navController.navigate(Routes.customAlbum(albumId, albumName)) },
                onOpenTrash = { navController.navigate(Routes.TRASH) },
                onOpenCloudSync = { navController.navigate(Routes.CLOUD_SYNC) },
            )
        }

        composable(
            route = Routes.gridPattern(),
            arguments = listOf(
                navArgument("bucketId") { type = NavType.LongType },
                navArgument("bucketName") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val bucketId = backStackEntry.arguments?.getLong("bucketId") ?: -1L
            val bucketName = Routes.decodeName(backStackEntry.arguments?.getString("bucketName").orEmpty())
            MediaGridScreen(
                bucketId = bucketId,
                bucketName = bucketName,
                viewModel = galleryViewModel,
                onNavigateUp = { navController.popBackStack() },
                onOpenViewer = { item ->
                    if (item.type == MediaType.VIDEO) {
                        navController.navigate(Routes.video(item.id))
                    } else {
                        navController.navigate(Routes.imageViewer(bucketId, item.id))
                    }
                },
                onStartSlideshow = { navController.navigate(Routes.slideshow(bucketId)) },
            )
        }

        composable(
            route = Routes.imageViewerPattern(),
            arguments = listOf(
                navArgument("bucketId") { type = NavType.LongType },
                navArgument("itemId") { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val bucketId = backStackEntry.arguments?.getLong("bucketId") ?: -1L
            val itemId = backStackEntry.arguments?.getLong("itemId") ?: -1L
            ImageViewerScreen(
                bucketId = bucketId,
                startItemId = itemId,
                viewModel = galleryViewModel,
                onBack = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() },
                onEdit = { navController.navigate(Routes.editor(itemId)) },
            )
        }

        composable(
            route = Routes.videoPattern(),
            arguments = listOf(navArgument("itemId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getLong("itemId") ?: -1L
            val allItems by galleryViewModel.allItems.collectAsState()
            val item = allItems.find { it.id == itemId }
            if (item == null) {
                // Video wurde zwischenzeitlich gelöscht (z. B. von einer anderen App) — kein
                // Absturz auf einen jetzt ungültigen Verweis, einfach zurück.
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                VideoPlayerScreen(
                    item = item,
                    viewModel = galleryViewModel,
                    onBack = { navController.popBackStack() },
                    onDeleted = { navController.popBackStack() },
                )
            }
        }

        composable(
            route = Routes.customAlbumPattern(),
            arguments = listOf(
                navArgument("albumId") { type = NavType.StringType },
                navArgument("albumName") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val albumId = Routes.decodeName(backStackEntry.arguments?.getString("albumId").orEmpty())
            val albumName = Routes.decodeName(backStackEntry.arguments?.getString("albumName").orEmpty())
            CustomAlbumScreen(
                albumId = albumId,
                albumName = albumName,
                viewModel = galleryViewModel,
                onNavigateUp = { navController.popBackStack() },
                onOpenViewer = { item ->
                    if (item.type == MediaType.VIDEO) {
                        navController.navigate(Routes.video(item.id))
                    } else {
                        navController.navigate(Routes.imageViewer(ALL_BUCKET_ID, item.id))
                    }
                },
                onAlbumDeleted = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.slideshowPattern(),
            arguments = listOf(navArgument("bucketId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val bucketId = backStackEntry.arguments?.getLong("bucketId") ?: -1L
            SlideshowScreen(bucketId = bucketId, viewModel = galleryViewModel, onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.editorPattern(),
            arguments = listOf(navArgument("itemId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getLong("itemId") ?: -1L
            val allItems by galleryViewModel.allItems.collectAsState()
            val item = allItems.find { it.id == itemId }
            if (item == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                PhotoEditorScreen(
                    uri = item.uri,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }
        }

        composable(Routes.CLOUD_SYNC) {
            CloudSyncScreen(viewModel = galleryViewModel, onBack = { navController.popBackStack() })
        }

        composable(Routes.TRASH) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                TrashScreen(onBack = { navController.popBackStack() })
            } else {
                // Unerreichbar in der Praxis (Einstiegspunkt in AlbumsScreen ist ausgeblendet),
                // aber ohne diesen Zweig würde lint einen unbedingten Aufruf einer
                // @RequiresApi(R)-Funktion melden.
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
    }
}
