package de.ble1st.gallery.nav

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Der Kern dieses Tests ist analyse.md 4-08 (**Hoch**): `ACTION_VIEW` akzeptierte jede Uri mit
 * numerischem letztem Pfadsegment, also hätte ein `content://fremde.app/item/42` das
 * MediaStore-Element 42 dieses Geräts geöffnet — ein fremdes Bild, ohne erkennbare Ablehnung.
 *
 * Der Befund war behoben, aber nicht abgesichert: die Prüfung lag als privater Block in
 * `MainActivity` und war nur über einen echten Activity-Start erreichbar. Sie liegt seit
 * 2026-09-04 in [ExternalIntent.from] (reines Intent → ExternalIntent, ohne Context) — genau
 * deshalb, damit dieser Test existieren kann.
 */
@RunWith(AndroidJUnit4::class)
class ExternalIntentInstrumentedTest {

    /** Steht für den `ContentResolver.getType`-Rückfall — die Tests geben ihn explizit vor. */
    private fun resolver(type: String? = null): (Uri) -> String? = { type }

    @Test
    fun launcherIntentIsNotExternal() {
        assertNull(ExternalIntent.from(Intent(Intent.ACTION_MAIN), resolver()))
    }

    @Test
    fun nullIntentIsNotExternal() {
        assertNull(ExternalIntent.from(null, resolver()))
    }

    @Test
    fun mediaStoreViewUriIsAccepted() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://media/external/images/media/42"))
        val result = ExternalIntent.from(intent, resolver()) as ExternalIntent.ViewItem
        assertEquals(42L, result.itemId)
        assertEquals(false, result.isVideo)
    }

    @Test
    fun foreignAuthorityIsRejected() {
        // Der eigentliche Befund. Ohne Authority-Prüfung wäre das ExternalIntent.ViewItem(42).
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://com.other.provider/item/42"))
        assertNull(ExternalIntent.from(intent, resolver()))
    }

    @Test
    fun fileSchemeIsRejected() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/DCIM/42"))
        assertNull(ExternalIntent.from(intent, resolver()))
    }

    @Test
    fun mediaStoreUriWithoutNumericIdIsRejectedInsteadOfCrashing() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://media/external/images/media"))
        assertNull(ExternalIntent.from(intent, resolver()))
    }

    @Test
    fun viewIntentWithoutDataIsRejected() {
        assertNull(ExternalIntent.from(Intent(Intent.ACTION_VIEW), resolver()))
    }

    @Test
    fun intentTypeDecidesVideoViewer() {
        // setDataAndType statt setType: Intent.setType() löscht eine zuvor gesetzte Daten-Uri
        // (dokumentiertes Verhalten). Ein Intent mit Typ *und* Daten lässt sich nur so bauen —
        // genau das tun auch die echten Aufrufer ("Öffnen mit" aus ConneXias Kamera).
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://media/external/video/media/7"), "video/mp4")
        val result = ExternalIntent.from(intent, resolver()) as ExternalIntent.ViewItem
        assertEquals(7L, result.itemId)
        assertTrue(result.isVideo)
    }

    @Test
    fun resolverTypeIsUsedWhenTheIntentCarriesNone() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://media/external/video/media/7"))
        val result = ExternalIntent.from(intent, resolver("video/mp4")) as ExternalIntent.ViewItem
        assertTrue(result.isVideo)
    }

    @Test
    fun pickIntentCarriesItsMimeFilter() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
        assertEquals(ExternalIntent.Pick("image/*"), ExternalIntent.from(intent, resolver()))
    }

    @Test
    fun actionPickIsTreatedLikeGetContent() {
        val intent = Intent(Intent.ACTION_PICK).apply { type = "video/*" }
        assertEquals(ExternalIntent.Pick("video/*"), ExternalIntent.from(intent, resolver()))
    }

    @Test
    fun sendCarriesTheSharedUri() {
        val uri = Uri.parse("content://de.other.app/bild/1")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        assertEquals(ExternalIntent.Send(listOf(uri), "image/jpeg"), ExternalIntent.from(intent, resolver()))
    }

    @Test
    fun sendMultipleCarriesEveryUri() {
        val uris = arrayListOf(
            Uri.parse("content://de.other.app/bild/1"),
            Uri.parse("content://de.other.app/bild/2"),
        )
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        assertEquals(ExternalIntent.Send(uris, "image/*"), ExternalIntent.from(intent, resolver()))
    }

    @Test
    fun sendWithoutStreamExtraIsNotExternal() {
        val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        assertNull(ExternalIntent.from(intent, resolver()))
    }
}
