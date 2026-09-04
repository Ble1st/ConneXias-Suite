package de.ble1st.files.nav

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sichert den Encode-/Decode-Vertrag der Navigations-Routen ab (analyse.md 2-18 — „Doppeltes
 * URL-Decoding der Navigations-Parameter").
 *
 * Instrumentation und nicht JVM-Unit-Test, weil [Uri.encode]/[Uri.decode] Android-Framework-Code
 * sind: unter einem reinen JVM-Test liefert die gestubbte `android.jar` nur
 * `RuntimeException("Stub!")`. Genau dadurch war dieser Vertrag bisher überhaupt nicht getestet —
 * der Befund wurde per Code-Review gefunden, nicht von einem Test.
 *
 * Der Test bildet nach, was zur Laufzeit passiert: [Routes.browser] kodiert, Compose Navigation
 * dekodiert das Argument beim Template-Matching intern **einmal** mit [Uri.decode], und
 * [Routes.decodePathArg] reicht es unverändert durch. Kommt am Ende nicht exakt der
 * Ausgangspfad heraus, ist der Vertrag gebrochen.
 */
@RunWith(AndroidJUnit4::class)
class RoutesInstrumentedTest {

    /** Was Compose Navigation intern mit dem Argument macht (`NavDeepLink` → `Uri.decode`). */
    private fun navigationDecode(route: String, prefix: String): String =
        Routes.decodePathArg(Uri.decode(route.removePrefix(prefix)))

    private fun assertBrowserRoundTrip(path: String) {
        assertEquals(path, navigationDecode(Routes.browser(path), "browser/"))
    }

    @Test
    fun plainPathSurvivesRoundTrip() {
        assertBrowserRoundTrip("/storage/emulated/0/Download")
    }

    @Test
    fun pathWithSpacesSurvivesRoundTrip() {
        // URLEncoder würde hier "+" erzeugen, Uri.decode lässt "+" stehen — der Grund, warum
        // Routes.encode Uri.encode nutzt.
        assertBrowserRoundTrip("/storage/emulated/0/Meine Bilder/Urlaub 2024")
    }

    @Test
    fun pathWithEscapedSlashSurvivesRoundTrip() {
        // Der Fall aus dem Befund: ein Dateiname, der selbst ein prozentkodiertes Zeichen enthält.
        // Beim zweiten Decode wurde daraus ein echter Pfadtrenner.
        assertBrowserRoundTrip("/storage/emulated/0/foto%2Ftest.jpg")
    }

    @Test
    fun pathWithLonePercentSurvivesRoundTrip() {
        // URLDecoder warf hier IllegalArgumentException ("Incomplete trailing escape").
        assertBrowserRoundTrip("/storage/emulated/0/100%")
    }

    @Test
    fun pathWithPlusSignSurvivesRoundTrip() {
        assertBrowserRoundTrip("/storage/emulated/0/C++ Notizen.txt")
    }

    @Test
    fun pathWithUmlautsSurvivesRoundTrip() {
        assertBrowserRoundTrip("/storage/emulated/0/Größenübersicht/Straße.pdf")
    }

    @Test
    fun viewerRouteKeepsCategorySegmentSeparate() {
        val route = Routes.viewer("IMAGE", "/storage/emulated/0/a b/c.jpg")
        assertEquals("viewer/IMAGE/", route.substring(0, "viewer/IMAGE/".length))
        assertEquals(
            "/storage/emulated/0/a b/c.jpg",
            Routes.decodePathArg(Uri.decode(route.removePrefix("viewer/IMAGE/"))),
        )
    }

    @Test
    fun webdavRouteKeepsAccountIdSegmentSeparate() {
        val route = Routes.webdav("acc-1", "/Fotos/Urlaub 2024")
        assertEquals(
            "/Fotos/Urlaub 2024",
            Routes.decodePathArg(Uri.decode(route.removePrefix("webdav/acc-1/"))),
        )
    }

    @Test
    fun localShareRouteSurvivesRoundTrip() {
        val route = Routes.localShare("/storage/emulated/0/Freigabe Ordner")
        assertEquals(
            "/storage/emulated/0/Freigabe Ordner",
            Routes.decodePathArg(Uri.decode(route.removePrefix("localshare/"))),
        )
    }

    @Test
    fun encodedPathContainsNoRawSlash() {
        // Die eigentliche Aufgabe des Encodings: Compose Navigation darf den Pfad nicht in
        // mehrere Routen-Segmente aufsplitten.
        val encoded = Routes.browser("/storage/emulated/0/a/b").removePrefix("browser/")
        assertEquals(-1, encoded.indexOf('/'))
    }
}
