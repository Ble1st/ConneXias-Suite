package de.ble1st.camera.nav

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.ble1st.camera.permission.CameraPermission
import de.ble1st.camera.ui.capture.CaptureScreen
import de.ble1st.camera.ui.onboarding.CameraPermissionScreen
import de.ble1st.camera.ui.review.CaptureReviewScreen

/** `captureRequestInfo` != null, `onCaptureDelivered`/`onCaptureCanceled` != No-Op nur, wenn
 * [de.ble1st.camera.MainActivity] per `ACTION_IMAGE_CAPTURE`/`ACTION_VIDEO_CAPTURE` gestartet
 * wurde (s. [CaptureRequestInfo]) — beim normalen App-Start (Launcher-Icon) bleiben alle drei auf
 * ihren No-Op-Defaults, die Kurz-Ansicht zeigt dann keinen "Verwenden"-Knopf. */
@Composable
fun CameraNavHost(
    captureRequestInfo: CaptureRequestInfo? = null,
    onCaptureDelivered: (Uri?) -> Unit = {},
    onCaptureCanceled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    NavHost(navController = navController, startDestination = Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            val lifecycleOwner = LocalLifecycleOwner.current
            var hasAccess by remember { mutableStateOf(CameraPermission.hasAccess(context)) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { results -> hasAccess = results.values.all { it } }

            // CAMERA/RECORD_AUDIO können auch über die System-Einstellungen (statt den Dialog
            // oben) nachträglich gewährt werden, z. B. nach einem vorherigen "Nicht mehr
            // fragen" — Re-Check bei jedem ON_RESUME fängt diesen Fall ab, dasselbe Muster wie
            // ConneXias Files' Onboarding-Route.
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) hasAccess = CameraPermission.hasAccess(context)
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            LaunchedEffect(hasAccess) {
                if (hasAccess) {
                    navController.navigate(Routes.CAPTURE) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            }

            if (!hasAccess) {
                CameraPermissionScreen(onRequestAccess = { permissionLauncher.launch(CameraPermission.required) })
            }
        }

        composable(Routes.CAPTURE) {
            // Zurück verlässt bei einem laufenden Kamera-Contract-Aufruf die App komplett statt
            // nur den Screen — der Aufrufer (Messenger/Browser/...) erwartet ein `RESULT_CANCELED`,
            // kein hängendes/leeres Ergebnis.
            BackHandler(enabled = captureRequestInfo != null) { onCaptureCanceled() }
            CaptureScreen(
                captureRequestInfo = captureRequestInfo,
                onOpenReview = { uri, isVideo -> navController.navigate(Routes.review(uri, isVideo)) },
            )
        }

        composable(
            route = Routes.reviewPattern(),
            arguments = listOf(
                navArgument("isVideo") { type = NavType.BoolType },
                navArgument("uri") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val isVideo = backStackEntry.arguments?.getBoolean("isVideo") ?: false
            val uri = Routes.decodeUriArg(backStackEntry.arguments?.getString("uri").orEmpty())
            CaptureReviewScreen(
                uri = uri,
                isVideo = isVideo,
                captureRequestInfo = captureRequestInfo,
                onBack = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() },
                onUseCapture = onCaptureDelivered,
            )
        }
    }
}
