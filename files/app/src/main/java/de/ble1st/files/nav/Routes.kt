package de.ble1st.files.nav

import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

/**
 * Ordner-/Dateipfade enthalten Schrägstriche und landen als einzelnes Navigations-Argument in der
 * Route ("browser/{path}") — URL-Encoding, damit Compose Navigation den Pfad nicht selbst in
 * mehrere Segmente aufsplittet.
 */
private fun encode(path: String) = URLEncoder.encode(path, UTF_8.name())
private fun decode(encoded: String) = URLDecoder.decode(encoded, UTF_8.name())

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    private const val BROWSER_PATTERN = "browser/{path}"
    private const val VIEWER_PATTERN = "viewer/{category}/{path}"
    private const val WEBDAV_PATTERN = "webdav/{accountId}/{path}"

    fun browserPattern() = BROWSER_PATTERN
    fun viewerPattern() = VIEWER_PATTERN
    fun webdavPattern() = WEBDAV_PATTERN

    fun browser(path: String) = "browser/${encode(path)}"
    fun viewer(category: String, path: String) = "viewer/$category/${encode(path)}"
    fun webdav(accountId: String, path: String) = "webdav/$accountId/${encode(path)}"

    fun decodePathArg(encoded: String): String = decode(encoded)
}
