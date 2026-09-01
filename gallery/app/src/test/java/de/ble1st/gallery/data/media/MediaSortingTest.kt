package de.ble1st.gallery.data.media

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

/** s. BucketGroupingTest-Klassendoc zur Begründung des Mockito-Uri-Platzhalters. */
class MediaSortingTest {

    private fun item(name: String, date: Long, size: Long) = MediaItem(
        id = date,
        uri = mock(Uri::class.java),
        displayName = name,
        bucketId = 1,
        bucketName = "Camera",
        type = MediaType.IMAGE,
        dateAddedSeconds = date,
        sizeBytes = size,
        width = 0,
        height = 0,
        path = "",
    )

    private val items = listOf(
        item("banana.jpg", date = 100, size = 3_000),
        item("Apple.jpg", date = 300, size = 1_000),
        item("cherry.jpg", date = 200, size = 2_000),
    )

    @Test
    fun `sort by date is descending (newest first)`() {
        val sorted = sortedItems(items, SortOrder.DATE)
        assertEquals(listOf(300L, 200L, 100L), sorted.map { it.dateAddedSeconds })
    }

    @Test
    fun `sort by name is case-insensitive ascending`() {
        val sorted = sortedItems(items, SortOrder.NAME)
        assertEquals(listOf("Apple.jpg", "banana.jpg", "cherry.jpg"), sorted.map { it.displayName })
    }

    @Test
    fun `sort by size is descending (largest first)`() {
        val sorted = sortedItems(items, SortOrder.SIZE)
        assertEquals(listOf(3_000L, 2_000L, 1_000L), sorted.map { it.sizeBytes })
    }
}
