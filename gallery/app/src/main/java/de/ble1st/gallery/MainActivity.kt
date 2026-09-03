package de.ble1st.gallery

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
                                // analyse.md (2. Durchgang, Mittel): FLAG_GRANT_READ_URI_PERMISSION
                                // allein reicht für einen dauerhaften Zugriff nicht — ein Aufrufer,
                                // der die Uri über den Prozesslebenszyklus hinaus behalten will,
                                // ruft `ContentResolver.takePersistableUriPermission` auf; das
                                // scheitert mit SecurityException, wenn der gebende Intent nicht
                                // auch FLAG_GRANT_PERSISTABLE_URI_PERMISSION gesetzt hat.
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
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
        Intent.ACTION_SEND -> sendUri(intent)?.let { ExternalIntent.Send(listOf(it), intent.type) }
        Intent.ACTION_SEND_MULTIPLE -> sendMultipleUris(intent)?.let { ExternalIntent.Send(it, intent.type) }
        else -> null
    }

    /** "Teilen mit ConneXias Galerie" — dasselbe EXTRA_STREAM-Muster wie ConneXias Files'
     * `data/share/IncomingShare.kt`, hier unabhängig dupliziert (kein Code wird zwischen den Apps
     * geteilt, s. Plan-Klassendoc). */
    private fun sendUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun sendMultipleUris(intent: Intent): List<Uri>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }

    private fun viewItemFromUri(uri: Uri?): ExternalIntent.ViewItem? {
        if (uri == null) return null
        // analyse.md (2. Durchgang, Hoch): vorher akzeptierte diese Funktion JEDE Uri, deren
        // letztes Pfadsegment zufällig eine Zahl war — ContentUris.parseId kennt keine Authority-
        // Prüfung. Ein `content://com.other.provider/item/42` hätte MediaStore-Element 42 geöffnet
        // (falsches Bild, keine erkennbare Ablehnung) statt regulär zu Albums zu gehen wie bei
        // einer wirklich unbrauchbaren Uri. `MediaStore.AUTHORITY` ("media") ist die einzige
        // Authority, unter der `parseId` hier je einen sinnvollen Treffer liefern kann.
        if (uri.authority != MediaStore.AUTHORITY) return null
        // ContentUris.parseId schlägt für eine Uri fehl, die nicht mit einer numerischen ID endet
        // (z. B. ein fremder DocumentsProvider-Pfad) — in dem Fall gibt es hier keinen sinnvollen
        // MediaStore-Eintrag zu zeigen, also regulär zu Albums statt abzustürzen.
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
        val mimeType = intent.type ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        return ExternalIntent.ViewItem(id, isVideo = mimeType?.startsWith("video/") == true)
    }
}
