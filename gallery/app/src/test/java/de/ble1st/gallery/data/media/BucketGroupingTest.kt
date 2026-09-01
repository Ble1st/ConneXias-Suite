package de.ble1st.gallery.data.media

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

/** [groupIntoBuckets] ist reine Listenlogik ohne MediaStore-Zugriff — [MediaItem.uri] wird dabei
 * nie inhaltlich ausgewertet, ein Mockito-Mock genügt als Platzhalter-Referenz (echte
 * `Uri.parse()`/`Uri.EMPTY`-Aufrufe würden im Android-Stub-JAR ohne Robolectric werfen). */
class BucketGroupingTest {

    private fun item(id: Long, bucketId: Long, bucketName: String, dateAdded: Long) = MediaItem(
        id = id,
        uri = mock(Uri::class.java),
        displayName = "item_$id",
        bucketId = bucketId,
        bucketName = bucketName,
        type = MediaType.IMAGE,
        dateAddedSeconds = dateAdded,
        sizeBytes = 0,
        width = 0,
        height = 0,
        path = "",
    )

    @Test
    fun `groups items by bucket and counts them`() {
        val items = listOf(
            item(1, bucketId = 10, bucketName = "Camera", dateAdded = 300),
            item(2, bucketId = 10, bucketName = "Camera", dateAdded = 200),
            item(3, bucketId = 20, bucketName = "Screenshots", dateAdded = 100),
        )

        val buckets = groupIntoBuckets(items)

        assertEquals(2, buckets.size)
        val camera = buckets.first { it.id == 10L }
        assertEquals("Camera", camera.name)
        assertEquals(2, camera.itemCount)
        val screenshots = buckets.first { it.id == 20L }
        assertEquals(1, screenshots.itemCount)
    }

    @Test
    fun `cover is the first (most recent) item of the bucket`() {
        // groupIntoBuckets erwartet bereits absteigend nach Datum sortierte Eingabe.
        val newest = item(1, bucketId = 10, bucketName = "Camera", dateAdded = 300)
        val older = item(2, bucketId = 10, bucketName = "Camera", dateAdded = 100)

        val buckets = groupIntoBuckets(listOf(newest, older))

        assertEquals(newest.uri, buckets.single().coverUri)
    }

    @Test
    fun `empty list yields no buckets`() {
        assertEquals(0, groupIntoBuckets(emptyList()).size)
    }
}
