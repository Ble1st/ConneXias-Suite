package de.ble1st.camera.data.camera

/**
 * Die vom Gerätehersteller im Camera2-HAL bereitgestellten "Camera Extensions", so wie
 * [androidx.camera.extensions.ExtensionsManager] sie anbietet — AndroidX/FOSS, kein Cloud-/
 * Play-Services-Dienst.
 *
 * Bis 2026-09-03 gab es hier nur einen HDR-An/Aus-Schalter. Die übrigen Modi kosten praktisch
 * nichts zusätzlich: es ist derselbe `getExtensionEnabledCameraSelector`-Aufruf mit einer anderen
 * Konstante, und die Verfügbarkeitsprüfung (`isExtensionAvailable`) lief ohnehin schon pro Bind.
 * Deshalb jetzt eine Auswahl statt eines Schalters — jeder Modus wird nur angeboten, wenn das
 * gerade gebundene Objektiv ihn tatsächlich meldet (Extension-Support ist geräte- *und*
 * objektivabhängig; die Frontkamera bietet oft weniger an als die Rückkamera).
 *
 * Alle Extensions gelten nur für den Foto-Modus: die Extension-Selektoren unterstützen keine
 * gleichzeitige `VideoCapture`-Bindung (s. [CameraController]-Klassendoc).
 *
 * Bewusst **ohne** Bezug auf `androidx.camera.extensions.ExtensionMode`: die Zuordnung auf die
 * CameraX-Konstante steht in [CameraController.extensionModeOf]. Damit bleibt dieser Enum reines
 * Kotlin und kann in `CaptureUiState` stehen, ohne dass die JVM-Unit-Tests über diese Klasse
 * CameraX-Klassen initialisieren müssten (genau daran sind sie beim ersten Anlauf gescheitert).
 *
 * `FACE_RETOUCH` ist bewusst **nicht** enthalten, obwohl `ExtensionMode` es anbietet — ein
 * Schönheitsfilter, der Gesichter automatisch verändert, ist keine Aufnahmefunktion, sondern eine
 * Bildmanipulation; die App bietet Nachbearbeitung ausschließlich sichtbar und non-destruktiv in
 * der Kurz-Ansicht an (s. `util/PhotoFilters.kt`).
 */
enum class CameraExtension {
    /** Kein Extension-Selektor — der normale, unveränderte Aufnahmepfad. */
    NONE,

    /** Überlässt dem Gerät die Wahl zwischen seinen eigenen Extensions je nach Szene. */
    AUTO,

    HDR,

    NIGHT,

    /** Tiefenschärfe-Simulation ("Porträtmodus"). */
    BOKEH,
    ;

    companion object {
        /** [NONE] braucht keinen Selektor und ist deshalb immer verfügbar — nur die übrigen Modi
         * müssen beim Binden gegen das Gerät geprüft werden. */
        val selectable: List<CameraExtension> = entries.filter { it != NONE }
    }
}
