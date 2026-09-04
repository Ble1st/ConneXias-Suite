package de.ble1st.camera

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.ble1st.camera.nav.CameraNavHost
import de.ble1st.camera.nav.captureRequestInfoFromIntent
import de.ble1st.camera.ui.theme.CameraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Nur beim Start ausgewertet (nicht in onNewIntent nachgezogen) — anders als die
        // Teilen-/Öffnen-mit-Empfangslogik in Files/Gallery ist diese Activity nicht
        // singleTop/singleTask deklariert, ein System-Kamera-Contract-Aufrufer startet also immer
        // eine frische Instanz.
        val captureRequestInfo = captureRequestInfoFromIntent(intent)
        setContent {
            CameraTheme {
                CameraNavHost(
                    captureRequestInfo = captureRequestInfo,
                    onCaptureDelivered = { deliveredUri -> finishWithCaptureResult(deliveredUri) },
                    onCaptureCanceled = { finishCaptureCanceled() },
                )
            }
        }
    }

    /** Schließt den System-Kamera-Contract erfolgreich ab — `deliveredUri` ist die Uri, die der
     * Aufrufer ohne eigene `EXTRA_OUTPUT` zurückbekommt (s. [de.ble1st.camera.nav.CaptureRequestInfo]-
     * Klassendoc); war `EXTRA_OUTPUT` gesetzt, wurde bereits dorthin geschrieben und `deliveredUri`
     * ist `null` (kein zusätzliches `data` nötig). */
    private fun finishWithCaptureResult(deliveredUri: Uri?) {
        val resultIntent = Intent().apply {
            if (deliveredUri != null) {
                data = deliveredUri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun finishCaptureCanceled() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
