package de.ble1st.gallery

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.ble1st.gallery.nav.ExternalIntent
import de.ble1st.gallery.nav.GalleryNavHost
import de.ble1st.gallery.ui.theme.GalleryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val externalIntent = resolveExternalIntent()
        setContent {
            GalleryTheme {
                GalleryNavHost(
                    externalIntent = externalIntent,
                    onPicked = { uri ->
                        setResult(
                            RESULT_OK,
                            Intent().apply {
                                data = uri
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            },
                        )
                        finish()
                    },
                )
            }
        }
    }

    /** Erkennt, ob die App gerade als "Öffnen mit"-Ziel (ACTION_VIEW, z. B. von ConneXias Kamera)
     * oder als Bild-/Video-Auswahl für eine fremde App (ACTION_PICK/ACTION_GET_CONTENT) gestartet
     * wurde, statt normal über den Launcher — s. ExternalIntent-Doc. */
    private fun resolveExternalIntent(): ExternalIntent? = when (intent?.action) {
        Intent.ACTION_VIEW -> viewItemFromUri(intent.data)
        Intent.ACTION_PICK, Intent.ACTION_GET_CONTENT -> ExternalIntent.Pick(intent.type)
        else -> null
    }

    private fun viewItemFromUri(uri: Uri?): ExternalIntent.ViewItem? {
        if (uri == null) return null
        // ContentUris.parseId schlägt für eine Uri fehl, die nicht mit einer numerischen ID endet
        // (z. B. ein fremder DocumentsProvider-Pfad) — in dem Fall gibt es hier keinen sinnvollen
        // MediaStore-Eintrag zu zeigen, also regulär zu Albums statt abzustürzen.
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
        val mimeType = intent.type ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        return ExternalIntent.ViewItem(id, isVideo = mimeType?.startsWith("video/") == true)
    }
}
