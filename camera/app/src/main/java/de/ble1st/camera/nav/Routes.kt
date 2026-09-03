package de.ble1st.camera.nav

import android.net.Uri
import androidx.core.net.toUri
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

/** Uri der Aufnahme landet als einzelnes Navigations-Argument in der Route ("review/{isVideo}/
 * {uri}") — URL-Encoding, damit Compose Navigation die enthaltenen Schrägstriche/Doppelpunkte
 * nicht selbst in mehrere Segmente aufsplittet (dasselbe Muster wie ConneXias Files' Routes.kt
 * für Dateipfade). */
private fun encode(value: String) = URLEncoder.encode(value, UTF_8.name())

object Routes {
    const val ONBOARDING = "onboarding"
    const val CAPTURE = "capture"
    // Kein Argument in der Route selbst — der gescannte Rohtext läuft über ScanResultHolder
    // (s. dortiges Klassendoc), nicht als Navigations-Argument.
    const val SCAN_RESULT = "scan_result"
    private const val REVIEW_PATTERN = "review/{isVideo}/{uri}"

    fun reviewPattern() = REVIEW_PATTERN
    fun review(uri: Uri, isVideo: Boolean) = "review/$isVideo/${encode(uri.toString())}"

    // analyse.md (2. Durchgang, Mittel): Compose Navigation dekodiert Pfad-Argumente beim
    // Uri-Template-Matching bereits selbst (`NavDeepLink` ruft intern `Uri.decode()` auf jedes
    // Segment) — ein zusätzlicher `URLDecoder.decode()`-Aufruf hier dekodierte ein zweites Mal.
    // Für eine Uri mit z. B. codiertem "%2F" im Pfad (kommt in content://-Uris real vor) wurde so
    // aus dem eigentlichen "%2F" ein "/", die rekonstruierte Uri war falsch; ein Rest-"%" am Ende
    // ließ `URLDecoder` sogar mit `IllegalArgumentException` abstürzen. `backStackEntry.arguments`
    // liefert den bereits einmal dekodierten Wert, hier reicht `.toUri()` direkt.
    fun decodeUriArg(value: String): Uri = value.toUri()
}
