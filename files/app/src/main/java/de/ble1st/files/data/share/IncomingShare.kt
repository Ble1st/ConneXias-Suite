package de.ble1st.files.data.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Von einer fremden App per "Teilen mit ConneXias Files" empfangene Uris (ACTION_SEND/
 * ACTION_SEND_MULTIPLE) — [de.ble1st.files.MainActivity] befüllt das bei onCreate/onNewIntent,
 * [de.ble1st.files.ui.home.HomeScreen] zeigt einen Hinweis-Banner dazu an,
 * [de.ble1st.files.ui.browser.FileBrowserScreen] konsumiert es und kopiert die Dateien in den
 * gerade geöffneten Ordner (dieselbe Kopierlogik wie beim SAF-Import, s.
 * FileBrowserViewModel.importUris). Ein simples Singleton-StateFlow statt eines
 * Navigation-Arguments, weil eine Liste von Uris nicht sauber durch eine String-Route passt — das
 * gleiche Muster wie ClipboardHolder für Zwischenablage-Inhalte.
 */
object IncomingShare {
    private val _pending = MutableStateFlow<List<Uri>?>(null)
    val pending: StateFlow<List<Uri>?> = _pending

    fun setFromIntent(intent: Intent?) {
        val uris = extractUris(intent)
        if (!uris.isNullOrEmpty()) _pending.value = uris
    }

    fun consume(): List<Uri> {
        val uris = _pending.value.orEmpty()
        _pending.value = null
        return uris
    }

    private fun extractUris(intent: Intent?): List<Uri>? = when (intent?.action) {
        Intent.ACTION_SEND -> singleStreamExtra(intent)?.let { listOf(it) }
        Intent.ACTION_SEND_MULTIPLE -> multipleStreamExtra(intent)
        else -> null
    }

    private fun singleStreamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun multipleStreamExtra(intent: Intent): List<Uri>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
}
