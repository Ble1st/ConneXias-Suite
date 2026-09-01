package de.ble1st.camera.data.storage

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreSaverTest {

    @Test
    fun `generateDisplayName has expected prefix and timestamp format`() {
        val timestamp = Date(0L) // 1970-01-01T00:00:00.000Z in der jeweiligen JVM-Standardzeitzone
        val name = MediaStoreSaver.generateDisplayName("IMG", timestamp)

        assertTrue("erwarteter Präfix 'IMG_', war: $name", name.startsWith("IMG_"))
        // yyyyMMdd_HHmmssSSS -> "IMG_" (4) + 8 Ziffern Datum + "_" (1) + 9 Ziffern Uhrzeit = 22 Zeichen
        assertEquals(22, name.length)
        assertTrue(
            "erwartetes Format IMG_yyyyMMdd_HHmmssSSS, war: $name",
            name.matches(Regex("IMG_\\d{8}_\\d{9}")),
        )
    }

    @Test
    fun `different prefixes are preserved`() {
        val timestamp = Date(0L)
        assertTrue(MediaStoreSaver.generateDisplayName("VID", timestamp).startsWith("VID_"))
    }
}
