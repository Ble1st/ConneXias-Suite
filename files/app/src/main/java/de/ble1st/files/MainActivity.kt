package de.ble1st.files

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.ble1st.files.data.share.IncomingShare
import de.ble1st.files.nav.FilesNavHost
import de.ble1st.files.ui.theme.FilesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        IncomingShare.setFromIntent(intent)
        setContent {
            FilesTheme {
                FilesNavHost()
            }
        }
    }

    // launchMode="singleTop" (Manifest) hält bei einem zweiten "Teilen mit ConneXias Files" die
    // bereits laufende Activity-Instanz statt eine neue anzulegen — ohne diesen Override würde der
    // neue Intent dann verworfen statt IncomingShare zu aktualisieren.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        IncomingShare.setFromIntent(intent)
    }
}
