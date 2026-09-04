package de.ble1st.files

import android.content.ClipData
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

    /**
     * Antwort auf ACTION_GET_CONTENT (s. [de.ble1st.files.data.share.PickRequest]) — die im
     * Datei-Browser ausgewählten Dateien gehen als content://-Uris mit befristeter
     * Leseberechtigung an den Aufrufer zurück, genau wie beim normalen "Öffnen mit"/"Teilen"
     * (FileActions.uriFor).
     *
     * Die beiden Fälle sind **nicht** austauschbar, deshalb die Fallunterscheidung: Eine einzelne
     * Datei muss in `setData` stehen, weil praktisch jeder Aufrufer `data.data` liest und ein
     * reines ClipData mit einem Eintrag dort schlicht nicht ankommt. Mehrere Dateien gehen
     * ausschließlich über [ClipData] — das ist der einzige Weg, den `EXTRA_ALLOW_MULTIPLE`
     * vorsieht. Zusätzlich bekommt das ClipData in beiden Mehrfach-Fällen dieselbe Uri auch als
     * `setData`, damit ein Aufrufer, der `EXTRA_ALLOW_MULTIPLE` gesetzt, aber das ClipData nie
     * ausgewertet hat, wenigstens die erste Datei erhält statt gar nichts.
     *
     * Das Flag muss am Intent selbst hängen (nicht an den einzelnen ClipData-Items) — die
     * Berechtigung wird vom System aus den Intent-Flags für alle enthaltenen Uris abgeleitet.
     */
    private fun finishWithPickResult(uris: List<Uri>) {
        if (uris.isEmpty()) {
            // Kann nur passieren, wenn der Bestätigen-Knopf ohne Auswahl durchkäme; dann lieber
            // ein sauberes "abgebrochen" als ein RESULT_OK ohne Inhalt, das der Aufrufer als
            // gültiges Ergebnis missversteht.
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val result = Intent()
            .setData(uris.first())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size > 1) {
            val clip = ClipData.newUri(contentResolver, "ConneXias Files", uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            result.clipData = clip
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
