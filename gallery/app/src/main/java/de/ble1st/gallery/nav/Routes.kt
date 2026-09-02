package de.ble1st.gallery.nav

import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

/** Album-/Uri-Namen landen als Navigations-Argumente in der Route — URL-Encoding, damit Compose
 * Navigation enthaltene Schrägstriche nicht selbst aufsplittet (dasselbe Muster wie ConneXias
 * Files' und ConneXias Kameras Routes.kt). */
private fun encode(value: String) = URLEncoder.encode(value, UTF_8.name())
private fun decode(value: String) = URLDecoder.decode(value, UTF_8.name())

object Routes {
    const val ONBOARDING = "onboarding"
    const val ALBUMS = "albums"
    const val TRASH = "trash"
    const val CLOUD_SYNC = "cloud_sync"
    private const val GRID_PATTERN = "grid/{bucketId}/{bucketName}"
    private const val IMAGE_VIEWER_PATTERN = "image/{bucketId}/{itemId}"
    private const val CUSTOM_ALBUM_IMAGE_VIEWER_PATTERN = "customAlbumImage/{albumId}/{itemId}"
    private const val VIDEO_PATTERN = "video/{itemId}"
    private const val CUSTOM_ALBUM_PATTERN = "customAlbum/{albumId}/{albumName}"
    private const val SLIDESHOW_PATTERN = "slideshow/{bucketId}"
    private const val EDITOR_PATTERN = "editor/{itemId}"

    fun gridPattern() = GRID_PATTERN
    fun imageViewerPattern() = IMAGE_VIEWER_PATTERN
    fun customAlbumImageViewerPattern() = CUSTOM_ALBUM_IMAGE_VIEWER_PATTERN
    fun videoPattern() = VIDEO_PATTERN
    fun customAlbumPattern() = CUSTOM_ALBUM_PATTERN
    fun slideshowPattern() = SLIDESHOW_PATTERN
    fun editorPattern() = EDITOR_PATTERN

    fun grid(bucketId: Long, bucketName: String) = "grid/$bucketId/${encode(bucketName)}"
    fun imageViewer(bucketId: Long, itemId: Long) = "image/$bucketId/$itemId"

    /** Bild-Betrachter, dessen Wisch-Geschwister auf ein benutzerdefiniertes Album beschränkt sind
     * (statt auf ein MediaStore-Bucket) — s. ImageViewerScreen.customAlbumId-Doc. */
    fun customAlbumImageViewer(albumId: String, itemId: Long) = "customAlbumImage/${encode(albumId)}/$itemId"
    fun video(itemId: Long) = "video/$itemId"
    fun customAlbum(albumId: String, albumName: String) = "customAlbum/${encode(albumId)}/${encode(albumName)}"
    fun slideshow(bucketId: Long) = "slideshow/$bucketId"
    fun editor(itemId: Long) = "editor/$itemId"

    fun decodeName(encoded: String): String = decode(encoded)
}
