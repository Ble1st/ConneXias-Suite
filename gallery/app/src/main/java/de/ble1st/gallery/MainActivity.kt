package de.ble1st.gallery

import android.content.Intent
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
     * wurde, statt normal über den Launcher. Die Auswertung selbst liegt in [ExternalIntent.from]
     * (s. dortiges Doc) — hier bleibt nur die Anbindung an den ContentResolver dieser Activity. */
    private fun resolveExternalIntent(): ExternalIntent? =
        ExternalIntent.from(intent) { uri -> contentResolver.getType(uri) }
}
