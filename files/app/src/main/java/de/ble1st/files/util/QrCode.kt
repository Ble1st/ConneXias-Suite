package de.ble1st.files.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR-Code-Erzeugung für den Link der WLAN/Hotspot-Freigabe (ui/localshare). Nur Erzeugen, kein
 * Scannen — deshalb reicht hier `com.google.zxing:core` statt der Scanner-Bibliothek, die Warden
 * und Kamera einsetzen (s. Kommentar in gradle/libs.versions.toml).
 *
 * Das Ergebnis ist bewusst **immer schwarz auf weiß**, unabhängig vom App-Theme: ein invertierter
 * QR-Code (helle Module auf dunklem Grund) widerspricht der Norm, und ein Teil der Scanner-Apps
 * erkennt ihn schlicht nicht. Der Aufrufer muss den Code deshalb auch im Dunkelmodus auf eine
 * weiße Fläche setzen.
 */
object QrCode {

    /**
     * Rendert [content] als quadratisches Bitmap mit [sizePx] Kantenlänge, oder `null`, wenn der
     * Inhalt sich nicht codieren lässt (zu lang für die gewählte Fehlerkorrektur).
     *
     * Blockierend, aber im Millisekundenbereich — der Aufrufer hält es trotzdem aus der
     * Composition heraus, weil die Bitmap-Allokation von der Bildschirmgröße abhängt.
     *
     * [ErrorCorrectionLevel.M] statt des höheren `Q`/`H`: der Code wird vom Bildschirm abgefotografiert,
     * nicht von einem zerkratzten Aufkleber — die zusätzliche Redundanz würde nur die Modulanzahl
     * erhöhen und damit die einzelnen Module kleiner (und schlechter erkennbar) machen.
     *
     * [EncodeHintType.MARGIN] auf 1 Modul statt der Vorgabe 4: die "Quiet Zone" liefert die
     * umgebende weiße Fläche in der UI ohnehin, der eingebaute Rand würde den nutzbaren Code
     * innerhalb der vorgegebenen Kantenlänge nur unnötig verkleinern.
     */
    fun encode(content: String, sizePx: Int): Bitmap? {
        if (content.isEmpty() || sizePx <= 0) return null
        val matrix = runCatching {
            QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to 1,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                ),
            )
        }.getOrNull() ?: return null

        val width = matrix.width
        val height = matrix.height
        // Pixelweise über setPixel() wären das bei 512×512 über 260.000 JNI-Übergänge; ein
        // vorbereitetes IntArray geht in einem Rutsch.
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
