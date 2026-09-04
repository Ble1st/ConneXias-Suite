package de.ble1st.gallery.nav

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Encode-/Decode-Vertrag der Album-/Bucket-Routen (analyse.md 4-19 — „Doppeltes URL-Decoding der
 * Album-/Bucket-Namen", plus Encoding-Mismatch: `URLEncoder`s „+" gegen `Uri.decode`).
 *
 * Instrumentation, weil [Uri] Framework-Code ist. Der Test bildet den echten Weg nach: [Routes]
 * kodiert, Compose Navigation dekodiert das Argument beim Template-Matching intern einmal mit
 * [Uri.decode], die App dekodiert selbst gar nicht mehr — `backStackEntry.arguments` liefert also
 * genau das, was hier herauskommen muss.
 */
@RunWith(AndroidJUnit4::class)
class RoutesInstrumentedTest {

    private fun bucketNameFrom(route: String, bucketId: Long): String =
        Uri.decode(route.removePrefix("grid/$bucketId/"))

    private fun assertGridRoundTrip(bucketName: String) {
        assertEquals(bucketName, bucketNameFrom(Routes.grid(7, bucketName), 7))
    }

    @Test
    fun plainBucketNameSurvivesRoundTrip() {
        assertGridRoundTrip("Camera")
    }

    @Test
    fun bucketNameWithSpaceSurvivesRoundTrip() {
        // Der namensgebende Fall des Befunds: "Urlaub 2024" kam als "Urlaub+2024" an.
        assertGridRoundTrip("Urlaub 2024")
    }

    @Test
    fun bucketNameWithSlashSurvivesRoundTrip() {
        assertGridRoundTrip("Bilder/Sortiert")
    }

    @Test
    fun bucketNameWithPlusSignSurvivesRoundTrip() {
        assertGridRoundTrip("A+B")
    }

    @Test
    fun bucketNameWithPercentSurvivesRoundTrip() {
        assertGridRoundTrip("100% fertig")
    }

    @Test
    fun bucketNameWithUmlautsSurvivesRoundTrip() {
        assertGridRoundTrip("Größenübersicht")
    }

    @Test
    fun bucketIdStaysItsOwnRouteSegment() {
        val route = Routes.grid(1234, "Bilder/Sortiert")
        assertEquals("grid/1234/", route.substring(0, "grid/1234/".length))
        // Der kodierte Name darf keinen rohen Schrägstrich mehr enthalten, sonst splittet
        // Compose Navigation ihn in ein zusätzliches Segment.
        assertEquals(-1, route.removePrefix("grid/1234/").indexOf('/'))
    }

    @Test
    fun customAlbumRouteEncodesBothIdAndName() {
        val albumId = "album/mit schrägstrich"
        val albumName = "Mein Album 2024"
        val route = Routes.customAlbum(albumId, albumName)
        val rest = route.removePrefix("customAlbum/")
        val parts = rest.split("/")
        assertEquals(2, parts.size)
        assertEquals(albumId, Uri.decode(parts[0]))
        assertEquals(albumName, Uri.decode(parts[1]))
    }

    @Test
    fun customAlbumImageViewerKeepsItemIdNumeric() {
        val route = Routes.customAlbumImageViewer("album/1", 99)
        val parts = route.removePrefix("customAlbumImage/").split("/")
        assertEquals(2, parts.size)
        assertEquals("album/1", Uri.decode(parts[0]))
        assertEquals(99L, parts[1].toLong())
    }

    @Test
    fun numericOnlyRoutesNeedNoEncoding() {
        assertEquals("image/3/42", Routes.imageViewer(3, 42))
        assertEquals("video/42", Routes.video(42))
        assertEquals("slideshow/3", Routes.slideshow(3))
        assertEquals("editor/42", Routes.editor(42))
    }
}
