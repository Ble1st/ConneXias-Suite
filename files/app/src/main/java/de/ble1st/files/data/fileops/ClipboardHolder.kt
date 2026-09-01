package de.ble1st.files.data.fileops

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ClipboardMode { COPY, CUT }

data class ClipboardContent(val paths: List<String>, val mode: ClipboardMode)

/**
 * App-weiter Kopier-/Ausschneide-Zwischenspeicher — bewusst kein Teil des per-Ordner
 * [de.ble1st.files.ui.browser.FileBrowserViewModel]-States, weil "Kopieren" in Ordner A und
 * "Einfügen" in Ordner B zwei verschiedene ViewModel-Instanzen sind (jede Navigation Compose
 * Backstack-Entry bekommt ihre eigene). Kein Android-Clipboard-API-Missbrauch (ClipboardManager
 * ist für Text/URIs zwischen Apps gedacht, hier reicht ein reiner In-Process-Zustand).
 */
object ClipboardHolder {
    private val _content = MutableStateFlow<ClipboardContent?>(null)
    val content: StateFlow<ClipboardContent?> = _content

    fun set(paths: List<String>, mode: ClipboardMode) {
        _content.value = ClipboardContent(paths, mode)
    }

    fun clear() {
        _content.value = null
    }
}
