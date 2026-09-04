package de.ble1st.camera.data.scan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Trägt den zuletzt gescannten Rohtext von [de.ble1st.camera.ui.capture.CaptureScreen]s
 * ScanContract-Launcher zum Ergebnis-Bildschirm ([de.ble1st.camera.ui.scan.ScanResultScreen]) —
 * bewusst nicht als Navigations-Argument: ein Barcode-Inhalt kann beliebig lang und beliebig
 * strukturiert sein (Steuerzeichen, sehr lange Freitexte), URL-Encoding eines ganzen QR-Inhalts in
 * ein Routen-Segment wäre fragil, dieselbe Erfahrung, die ConneXias Files' eigenes `Routes.kt` für
 * Datei-Pfade schon dokumentiert. Dasselbe In-Prozess-Singleton-Muster wie ConneXias Files'
 * `data/share/PickRequest.kt`.
 */
object ScanResultHolder {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text

    fun set(value: String) {
        _text.value = value
    }

    /** Einmaliges Konsumieren statt nur Lesen — ein zurück-navigierter, dann per Prozess-Neustart
     * wiederhergestellter Ergebnis-Bildschirm soll keinen veralteten Scan aus einer früheren
     * Sitzung anzeigen. */
    fun consume(): String? {
        val value = _text.value
        _text.value = null
        return value
    }
}
