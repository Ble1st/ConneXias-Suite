package de.ble1st.camera.nav

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import de.ble1st.camera.data.camera.CaptureMode

/**
 * Trägt die Parameter eines System-Kamera-Contract-Aufrufs (`ACTION_IMAGE_CAPTURE`/
 * `ACTION_VIDEO_CAPTURE`, s. AndroidManifest.xml-Intent-Filter) von [de.ble1st.camera.MainActivity]
 * bis zur Kurz-Ansicht durch — vorher hatte diese App nur `MAIN`/`LAUNCHER`, Messenger/Browser/
 * Files konnten sie also nie als Aufnahmeziel für "Foto aufnehmen" nutzen (analyse.md Abschnitt
 * 3/9). `forcedMode` sperrt den Foto-/Video-Umschalter auf den angeforderten Modus — ein
 * Messenger, der ein Foto anfragt, soll nicht versehentlich ein Video zurückbekommen. `outputUri`
 * ist die optionale `EXTRA_OUTPUT`-Ziel-Uri des Aufrufers; fehlt sie, liefert die App stattdessen
 * die eigene MediaStore-Uri der Aufnahme zurück (pragmatischer Fallback statt des offiziellen,
 * seit Jahren kaum noch genutzten Thumbnail-in-"data"-Extra-Verhaltens für Aufrufer ohne
 * `EXTRA_OUTPUT`).
 */
data class CaptureRequestInfo(val forcedMode: CaptureMode, val outputUri: Uri?)

fun captureRequestInfoFromIntent(intent: Intent): CaptureRequestInfo? {
    val forcedMode = when (intent.action) {
        MediaStore.ACTION_IMAGE_CAPTURE -> CaptureMode.PHOTO
        MediaStore.ACTION_VIDEO_CAPTURE -> CaptureMode.VIDEO
        else -> return null
    }
    val outputUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
    }
    return CaptureRequestInfo(forcedMode, outputUri)
}
