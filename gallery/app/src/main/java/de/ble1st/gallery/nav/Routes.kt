package de.ble1st.gallery.nav

import android.net.Uri

/** Album-/Bucket-Namen landen als Navigations-Argumente in der Route — URL-Encoding, damit
 * Compose Navigation enthaltene Schrägstriche nicht selbst aufsplittet (dasselbe Muster wie
 * ConneXias Files' und ConneXias Kameras Routes.kt).
 *
 * analyse.md (2. Durchgang, "Danach" #11 — "Composition-Navigation"): Compose Navigation
 * dekodiert Pfad-Argumente beim Uri-Template-Matching bereits selbst (`NavDeepLink` ruft intern
 * `Uri.decode()` auf jedes Segment, auch für normales `navigate("route")` ohne echten Deep
 * Link) — ein zusätzlicher `URLDecoder.decode()`-Aufruf hier dekodierte ein zweites Mal. Zuvor
 * kam dazu noch ein Encoding-Mismatch: `URLEncoder` kodiert Leerzeichen als "+", `Uri.decode()`
 * versteht aber nur Prozent-Escapes und lässt ein "+" unverändert stehen — ein Album- oder
 * Bucket-Name mit Leerzeichen (z. B. "Urlaub 2024") kam nach dem (einfachen) Decodieren durch
 * Navigation als "Urlaub+2024" an. Jetzt kodiert [encode] mit `Uri.encode` (demselben Verfahren,
 * das Navigation intern zum Decodieren nutzt) und die App decodiert selbst gar nicht mehr —
 * `backStackEntry.arguments` liefert bereits den fertig decodierten Wert. */
private fun encode(value: String) = Uri.encode(value)

object Routes {
    const val ONBOARDING = "onboarding"
    const val ALBUMS = "albums"
    const val TRASH = "trash"
    const val CLOUD_SYNC = "cloud_sync"
    const val ABOUT = "about"
    const val LICENSES = "licenses"

    /** Zwischenstopp für ACTION_SEND/ACTION_SEND_MULTIPLE (s. ExternalIntent.Send) — der Import
     * über [de.ble1st.gallery.data.media.SharedMediaImporter] ist asynchroner IO, kann also anders
     * als ExternalIntent.ViewItem nicht in einem synchronen Sprung von ONBOARDING aus erledigt
     * werden. Kein Argument in der Route, weil die Uri-Liste (aus ExternalIntent.Send) direkt aus
     * dem NavHost-Funktionsparameter gelesen wird, nicht aus dem Backstack. */
    const val IMPORT_SHARE = "import_share"
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
}
