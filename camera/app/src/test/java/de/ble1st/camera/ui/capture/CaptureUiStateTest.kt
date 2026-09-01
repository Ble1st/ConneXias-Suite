package de.ble1st.camera.ui.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `isBusy` entscheidet, ob Modus-/Kamerawechsel und ein neuer Auslöser-Tap gesperrt sind (s.
 * CaptureViewModel.setMode/switchLens/onShutterPressed) — reine Zustandslogik ohne CameraX-
 * Abhängigkeit, deshalb ohne Instrumentierung testbar.
 */
class CaptureUiStateTest {

    @Test
    fun `idle state is not busy`() {
        assertFalse(CaptureUiState().isBusy)
    }

    @Test
    fun `countdown running is busy`() {
        assertTrue(CaptureUiState(countdownSecondsLeft = 3).isBusy)
    }

    @Test
    fun `active recording is busy`() {
        assertTrue(CaptureUiState(isRecording = true).isBusy)
    }
}
