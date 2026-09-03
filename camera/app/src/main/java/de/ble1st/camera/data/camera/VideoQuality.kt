package de.ble1st.camera.data.camera

/**
 * Wählbare Videoauflösung. Vorher war [FHD] in [CameraController] fest verdrahtet, mit der
 * Begründung "hält Dateigröße/Encoder-Last moderat" — das bleibt der Standard, ist aber keine
 * Entscheidung, die die App dem Nutzer abnehmen muss: wer 4K-Material will, hat auf einem Gerät,
 * das es kann, keinen Grund, es nicht zu bekommen.
 *
 * Bewusst **ohne** Bezug auf `androidx.camera.video.Quality`: die Zuordnung auf die
 * CameraX-Konstante steht in [CameraController.qualityOf]. `Quality.SD` und Verwandte sind
 * statische Felder, deren Klasseninitialisierung in einem reinen JVM-Unit-Test mit
 * `ExceptionInInitializerError` fehlschlägt — und dieser Enum steht über `CaptureUiState` in
 * genau so einem Test. Derselbe Grund wie bei [CameraExtension].
 *
 * Jeder Wert behält die bisherige `FallbackStrategy` — meldet die Hardware die gewünschte
 * Qualität nicht, wird automatisch auf ein unterstütztes niedrigeres Profil heruntergestuft,
 * statt den Aufnahmestart mit einem harten Fehler abzubrechen. Ein auf einem anderen Gerät
 * gespeicherter Wert kann deshalb gefahrlos wiederhergestellt werden, auch wenn das aktuelle
 * Gerät ihn nicht unterstützt.
 */
enum class VideoQuality {
    SD,
    HD,
    FHD,
    UHD,
    ;

    companion object {
        val DEFAULT = FHD
    }
}
