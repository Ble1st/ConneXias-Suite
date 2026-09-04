package de.ble1st.camera.nav

import android.net.Uri
import androidx.core.net.toUri

/** Uri der Aufnahme landet als einzelnes Navigations-Argument in der Route ("review/{isVideo}/
 * {uri}") — URL-Encoding, damit Compose Navigation die enthaltenen Schrägstriche/Doppelpunkte
 * nicht selbst in mehrere Segmente aufsplittet (dasselbe Muster wie ConneXias Files' Routes.kt
 * für Dateipfade).
 *
 * `Uri.encode` statt `URLEncoder.encode` (2026-09-04): die beiden kodieren ein Leerzeichen
 * unterschiedlich — `URLEncoder` als "+" (Form-Encoding), `Uri.encode` als "%20". Dekodiert wird
 * das Argument von Compose Navigation selbst, und zwar mit `Uri.decode`, das ein "+" unverändert
 * stehen lässt. Aus einem Leerzeichen in der Uri wurde damit auf dem Rückweg ein wörtliches "+".
 * Für die von `MediaStoreSaver` erzeugten `content://media/...`-Uris ist das nie aufgetreten
 * (numerische IDs, keine Leerzeichen), aber der Zeichensatz einer Uri liegt nicht in der Hand
 * dieser App — Files und Galerie haben denselben Mismatch bereits behoben (analyse.md 2-18/4-19),
 * hier stand er noch. Der Rückweg [decodeUriArg] bleibt unverändert. */
private fun encode(value: String) = Uri.encode(value)

object Routes {
    const val ONBOARDING = "onboarding"
    const val CAPTURE = "capture"
    // Kein Argument in der Route selbst — der gescannte Rohtext läuft über ScanResultHolder
    // (s. dortiges Klassendoc), nicht als Navigations-Argument.
    const val SCAN_RESULT = "scan_result"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val LICENSES = "licenses"
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
