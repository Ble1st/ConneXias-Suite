package de.ble1st.camera.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Setzt `FLAG_SECURE`, solange der aufrufende Composable im Kompositionsbaum ist (Sucher +
 * Review-Ansicht) — verhindert Screenshots/Screen-Recording/eine Vorschau in der App-Übersicht.
 * Vorher setzte keine der beiden Ansichten das Flag, obwohl ConneXias Warden es durchgängig tut —
 * in einer FOSS-Privacy-Suite auffällig, ausgerechnet die Kamera-App ungeschützt zu lassen (s.
 * analyse.md). Bewusst immer an statt als Einstellung: ein eigener Settings-Screen nur für diesen
 * einen Schalter wäre v1-Überbau, s. README "Noch nicht enthalten".
 */
@Composable
fun SecureScreenEffect() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
