package de.ble1st.gallery.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFormattersTest {

    @Test
    fun `bytes below 1024 are shown as bytes`() {
        assertEquals("512 B", MediaFormatters.formatSize(512))
    }

    @Test
    fun `kilobytes are formatted with one decimal`() {
        assertEquals("1.5 KB", MediaFormatters.formatSize(1536))
    }

    @Test
    fun `megabytes are formatted with one decimal`() {
        assertEquals("2.0 MB", MediaFormatters.formatSize(2 * 1024 * 1024L))
    }
}
