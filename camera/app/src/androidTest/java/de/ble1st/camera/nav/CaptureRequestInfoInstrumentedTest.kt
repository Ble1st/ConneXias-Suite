package de.ble1st.camera.nav

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.ble1st.camera.data.camera.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Der System-Kamera-Contract (analyse.md 5-03 / 3-12): eine fremde App fragt per
 * `ACTION_IMAGE_CAPTURE`/`ACTION_VIDEO_CAPTURE` eine Aufnahme an. Braucht Instrumentation, weil
 * `Intent.getParcelableExtra` echte Parcel-Serialisierung ist.
 *
 * Der wichtigste Fall ist [forcedModeIsPhotoForImageCapture]/[forcedModeIsVideoForVideoCapture]:
 * ein Messenger, der ein Foto anfragt, darf kein Video zurückbekommen.
 */
@RunWith(AndroidJUnit4::class)
class CaptureRequestInfoInstrumentedTest {

    @Test
    fun launcherIntentIsNoCaptureRequest() {
        assertNull(captureRequestInfoFromIntent(Intent(Intent.ACTION_MAIN)))
    }

    @Test
    fun forcedModeIsPhotoForImageCapture() {
        val info = requireNotNull(captureRequestInfoFromIntent(Intent(MediaStore.ACTION_IMAGE_CAPTURE)))
        assertEquals(CaptureMode.PHOTO, info.forcedMode)
    }

    @Test
    fun forcedModeIsVideoForVideoCapture() {
        val info = requireNotNull(captureRequestInfoFromIntent(Intent(MediaStore.ACTION_VIDEO_CAPTURE)))
        assertEquals(CaptureMode.VIDEO, info.forcedMode)
    }

    @Test
    fun outputUriIsCarriedThrough() {
        val target = Uri.parse("content://de.other.app/anhaenge/17")
        val info = requireNotNull(
            captureRequestInfoFromIntent(
                Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, target)
                },
            ),
        )
        assertEquals(target, info.outputUri)
    }

    @Test
    fun missingOutputUriIsNullNotACrash() {
        // Ohne EXTRA_OUTPUT liefert die App die eigene MediaStore-Uri zurück (s. Klassendoc) —
        // das ist ein gültiger Aufruf, kein Fehlerfall.
        val info = requireNotNull(captureRequestInfoFromIntent(Intent(MediaStore.ACTION_IMAGE_CAPTURE)))
        assertNull(info.outputUri)
    }
}
