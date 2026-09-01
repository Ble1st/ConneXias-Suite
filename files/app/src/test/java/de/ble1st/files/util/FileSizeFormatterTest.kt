package de.ble1st.files.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FileSizeFormatterTest {

    @Test
    fun `bytes below 1024 are shown as-is`() {
        assertEquals("512 B", formatFileSize(512))
    }

    @Test
    fun `kilobytes are formatted with one decimal`() {
        assertEquals("1.5 KB", formatFileSize(1536))
    }

    @Test
    fun `megabytes round trip`() {
        assertEquals("2.0 MB", formatFileSize(2L * 1024 * 1024))
    }
}
