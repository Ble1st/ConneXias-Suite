package de.ble1st.files

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.ble1st.files.data.share.IncomingShare
import de.ble1st.files.data.share.IncomingView
import de.ble1st.files.data.share.PickRequest
import de.ble1st.files.nav.FilesNavHost
import de.ble1st.files.ui.theme.FilesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyIntent(intent)
        setContent {
            FilesTheme {
                FilesNavHost(onPicked = ::finishWithPickResult)
            }
        }
    }

    // launchMode="singleTop" (Manifest) hält bei einem zweiten "Teilen mit ConneXias Files" die
    // bereits laufende Activity-Instanz statt eine neue anzulegen — ohne diesen Override würde der
    // neue Intent dann verworfen statt IncomingShare/IncomingView/PickRequest zu aktualisieren.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent?) {
        IncomingShare.setFromIntent(intent)
        IncomingView.setFromIntent(intent)
        PickRequest.setFromIntent(intent)
    }

    /** Antwort auf ACTION_GET_CONTENT (s. [de.ble1st.files.data.share.PickRequest]) — die im
     * Datei-Browser angetippte Datei geht als content://-Uri mit befristeter Leseberechtigung an
     * den Aufrufer zurück, genau wie beim normalen "Öffnen mit"/"Teilen" (FileActions.uriFor). */
    private fun finishWithPickResult(uri: Uri) {
        setResult(
            RESULT_OK,
            Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        finish()
    }
}
