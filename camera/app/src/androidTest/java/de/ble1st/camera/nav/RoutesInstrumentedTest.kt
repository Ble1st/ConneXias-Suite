package de.ble1st.camera.nav

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Encode-/Decode-Vertrag der Review-Route (analyse.md 3-16 — „Doppeltes URL-Decoding der
 * Review-Route"). Instrumentation, weil [Uri] Framework-Code ist und unter einem JVM-Unit-Test
 * nur `RuntimeException("Stub!")` liefert.
 *
 * Der Test bildet den echten Weg nach: [Routes.review] kodiert, Compose Navigation dekodiert das
 * Argument beim Template-Matching intern einmal mit [Uri.decode], [Routes.decodeUriArg] baut die
 * Uri daraus wieder auf.
 */
@RunWith(AndroidJUnit4::class)
class RoutesInstrumentedTest {

    private fun roundTrip(uri: Uri): Uri {
        val route = Routes.review(uri, isVideo = false)
        val argument = route.removePrefix("review/false/")
        return Routes.decodeUriArg(Uri.decode(argument))
    }

    @Test
    fun mediaStoreUriSurvivesRoundTrip() {
        val uri = Uri.parse("content://media/external/images/media/12345")
        assertEquals(uri, roundTrip(uri))
    }

    @Test
    fun fileProviderUriWithEncodedSlashSurvivesRoundTrip() {
        // Der Fall aus dem Befund: ein bereits prozentkodierter Schrägstrich in der Uri. Beim
        // zweiten Decode wurde daraus ein echter Pfadtrenner und die Uri zeigte woandershin.
        val uri = Uri.parse("content://de.ble1st.camera.fileprovider/aufnahmen/foto%2Ftest.jpg")
        assertEquals(uri, roundTrip(uri))
    }

    @Test
    fun uriWithSpaceSurvivesRoundTrip() {
        // URLEncoder kodierte ein Leerzeichen als "+", Uri.decode lässt "+" stehen — daraus wurde
        // auf dem Rückweg ein wörtliches Pluszeichen (Encoding-Mismatch, 2026-09-04 behoben).
        val uri = Uri.parse("content://de.ble1st.camera.fileprovider/Meine Aufnahmen/a.jpg")
        assertEquals(uri, roundTrip(uri))
    }

    @Test
    fun uriWithPlusSignSurvivesRoundTrip() {
        val uri = Uri.parse("content://de.ble1st.camera.fileprovider/a+b.jpg")
        assertEquals(uri, roundTrip(uri))
    }

    @Test
    fun uriWithTrailingPercentSurvivesRoundTrip() {
        // URLDecoder warf hier IllegalArgumentException und riss die Kurz-Ansicht mit.
        val uri = Uri.parse("content://de.ble1st.camera.fileprovider/100%25.jpg")
        assertEquals(uri, roundTrip(uri))
    }

    @Test
    fun isVideoFlagStaysItsOwnRouteSegment() {
        val uri = Uri.parse("content://media/external/video/media/7")
        assertEquals("review/true/${Uri.encode(uri.toString())}", Routes.review(uri, isVideo = true))
    }

    @Test
    fun encodedUriContainsNoRawSlash() {
        // Der Zweck des Encodings: Compose Navigation darf die Uri nicht in mehrere
        // Routen-Segmente aufsplitten.
        val route = Routes.review(Uri.parse("content://media/external/images/media/1"), isVideo = false)
        assertEquals(-1, route.removePrefix("review/false/").indexOf('/'))
    }
}
