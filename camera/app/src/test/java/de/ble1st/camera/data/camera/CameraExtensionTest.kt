package de.ble1st.camera.data.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [CameraExtension] und [VideoQuality] sind bewusst framework-frei gehalten (s. deren
 * Klassendocs) — dieser Test ist zugleich die Absicherung dafür: Er lädt beide Enums in einem
 * reinen JVM-Test, was fehlschlägt, sobald jemand wieder ein `ExtensionMode`-/`Quality`-Feld
 * hineinzieht (`ExceptionInInitializerError`, genau der Fehler, an dem die erste Fassung
 * gescheitert ist).
 */
class CameraExtensionTest {

    @Test
    fun `selectable enthaelt NONE nicht`() {
        assertFalse(CameraExtension.NONE in CameraExtension.selectable)
    }

    @Test
    fun `selectable behaelt die Deklarationsreihenfolge`() {
        assertEquals(
            listOf(
                CameraExtension.AUTO,
                CameraExtension.HDR,
                CameraExtension.NIGHT,
                CameraExtension.BOKEH,
            ),
            CameraExtension.selectable,
        )
    }

    @Test
    fun `Standard-Videoqualitaet bleibt FHD`() {
        // Nicht kosmetisch: ein versehentlich auf UHD gehobener Default würde auf jedem Gerät ohne
        // ausdrückliche Nutzerwahl 4K aufnehmen (Dateigröße/Encoder-Last, s. VideoQuality-Doc).
        assertEquals(VideoQuality.FHD, VideoQuality.DEFAULT)
    }
}
