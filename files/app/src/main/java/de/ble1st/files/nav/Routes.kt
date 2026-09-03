package de.ble1st.files.nav

import android.net.Uri

/**
 * Ordner-/Dateipfade enthalten Schrägstriche und landen als einzelnes Navigations-Argument in der
 * Route ("browser/{path}") — URL-Encoding, damit Compose Navigation den Pfad nicht selbst in
 * mehrere Segmente aufsplittet.
 *
 * analyse.md (2. Durchgang, Hoch — "Doppeltes URL-Decoding der Nav-Argumente"): Compose
 * Navigations `NavType.StringType` dekodiert jedes Routen-Argument bereits selbst — Muster-Matching
 * läuft intern immer über `NavDeepLink` (auch für ganz normale `navigate("route")`-Aufrufe ohne
 * externen Deep-Link), und `NavDeepLink.kt` ruft `Uri.decode(matcher.group(...))` auf jedem
 * getroffenen Segment auf (verifiziert am tatsächlichen Quellcode aus
 * `navigation-common`-sources.jar, derselbe bereits für Camera bestätigte Befund, s. dortiges
 * `Routes.kt`). `decodePathArg` rief zusätzlich noch einmal `URLDecoder.decode` auf — ein bereits
 * dekodierter Pfad wie `foto%2Ftest.jpg` (enthält einen escapten Schrägstrich) wurde dadurch ein
 * zweites Mal dekodiert und der escapte Schrägstrich zu einem echten Pfadtrenner; ein Pfad mit
 * einem restlichen Prozentzeichen konnte `URLDecoder` sogar mit `IllegalArgumentException`
 * abstürzen lassen. [encode] nutzt jetzt [Uri.encode] statt `URLEncoder` (Form-Encoding, das
 * Leerzeichen als "+" statt "%20" kodiert) — das ist die zu `Uri.decode` passende Kodierung, sonst
 * käme aus Navs internem Decode für ein Leerzeichen ein wörtliches "+" statt eines Leerzeichens
 * zurück. [decodePathArg] gibt den bereits von Nav dekodierten Wert deshalb unverändert zurück.
 */
private fun encode(path: String) = Uri.encode(path)

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val TRASH = "trash"
    private const val BROWSER_PATTERN = "browser/{path}"
    private const val VIEWER_PATTERN = "viewer/{category}/{path}"
    private const val WEBDAV_PATTERN = "webdav/{accountId}/{path}"
    private const val LOCAL_SHARE_PATTERN = "localshare/{path}"

    fun browserPattern() = BROWSER_PATTERN
    fun viewerPattern() = VIEWER_PATTERN
    fun webdavPattern() = WEBDAV_PATTERN
    fun localSharePattern() = LOCAL_SHARE_PATTERN

    fun browser(path: String) = "browser/${encode(path)}"
    fun viewer(category: String, path: String) = "viewer/$category/${encode(path)}"
    fun webdav(accountId: String, path: String) = "webdav/$accountId/${encode(path)}"
    fun localShare(path: String) = "localshare/${encode(path)}"

    /** Kein eigener Decode-Aufruf mehr nötig — s. Moduldoc oben. Der Name bleibt (statt die drei
     * Aufrufstellen in `FilesNavHost.kt` auf den rohen `backStackEntry.arguments`-Wert umzustellen),
     * damit an jeder Stelle weiterhin klar ist, dass dieser Wert ursprünglich ein Routen-Argument
     * war, kein beliebiger String. */
    fun decodePathArg(value: String): String = value
}
