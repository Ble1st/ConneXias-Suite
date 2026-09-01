package de.ble1st.camera.nav

import android.net.Uri
import androidx.core.net.toUri
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

/** Uri der Aufnahme landet als einzelnes Navigations-Argument in der Route ("review/{isVideo}/
 * {uri}") — URL-Encoding, damit Compose Navigation die enthaltenen Schrägstriche/Doppelpunkte
 * nicht selbst in mehrere Segmente aufsplittet (dasselbe Muster wie ConneXias Files' Routes.kt
 * für Dateipfade). */
private fun encode(value: String) = URLEncoder.encode(value, UTF_8.name())
private fun decode(value: String) = URLDecoder.decode(value, UTF_8.name())

object Routes {
    const val ONBOARDING = "onboarding"
    const val CAPTURE = "capture"
    private const val REVIEW_PATTERN = "review/{isVideo}/{uri}"

    fun reviewPattern() = REVIEW_PATTERN
    fun review(uri: Uri, isVideo: Boolean) = "review/$isVideo/${encode(uri.toString())}"
    fun decodeUriArg(encoded: String): Uri = decode(encoded).toUri()
}
