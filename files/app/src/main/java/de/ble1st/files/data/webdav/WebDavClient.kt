package de.ble1st.files.data.webdav

import android.net.Uri
import de.ble1st.files.util.resolveMimeTypeForName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Schlanker WebDAV-Client auf OkHttp — PROPFIND/GET/PUT/DELETE/MKCOL/MOVE decken alle in
 * [de.ble1st.files.ui.webdav.WebDavBrowserScreen] angebotenen Operationen ab. Bewusst kein
 * Digest-Auth (nur Basic, per Header auf jedem Request statt eines OkHttp-Authenticators mit
 * 401-Retry) — Basic über HTTPS reicht für die große Mehrheit selbst gehosteter Server
 * (Nextcloud/ownCloud/Apache mod_dav); ein Server, der ausschließlich Digest verlangt, ist ein
 * bewusst in Kauf genommener Rand-Fall für v1.
 */
object WebDavClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val propfindBody = """<?xml version="1.0" encoding="utf-8" ?>
        |<d:propfind xmlns:d="DAV:">
        |  <d:prop>
        |    <d:resourcetype/>
        |    <d:getcontentlength/>
        |    <d:getlastmodified/>
        |  </d:prop>
        |</d:propfind>
    """.trimMargin()

    suspend fun list(account: WebDavAccount, path: String): Result<List<WebDavEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = urlFor(account, path)
            val request = authorizedRequest(account, url)
                .method("PROPFIND", propfindBody.toRequestBody("application/xml".toMediaType()))
                .header("Depth", "1")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("Leere Antwort")
                parseMultiStatus(body, basePathPrefix = baseHttpUrl(account).encodedPath, requestedUrl = url)
            }
        }
    }

    suspend fun download(account: WebDavAccount, path: String, destination: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = authorizedRequest(account, urlFor(account, path)).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Leere Antwort")
                    destination.parentFile?.mkdirs()
                    body.byteStream().use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
                    Unit
                }
            }
        }

    suspend fun upload(account: WebDavAccount, path: String, source: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mediaType = (resolveMimeTypeForName(source.name) ?: "application/octet-stream").toMediaType()
                val request = authorizedRequest(account, urlFor(account, path))
                    .put(source.asRequestBody(mediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                }
            }
        }

    suspend fun delete(account: WebDavAccount, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authorizedRequest(account, urlFor(account, path)).delete().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            }
        }
    }

    /** Legt einen Ordner an — [path] muss bereits mit "/" enden oder nicht, beides führt zum
     * selben Ziel-Collection-Pfad (s. [urlFor]-Segmentierung). */
    suspend fun mkdir(account: WebDavAccount, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authorizedRequest(account, urlFor(account, path)).method("MKCOL", null).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            }
        }
    }

    /** Umbenennen/Verschieben — WebDAV kennt keinen eigenen "Rename", MOVE mit dem gewünschten
     * Zielpfad im Destination-Header erledigt beides. `Overwrite: F` explizit gesetzt: RFC 4918s
     * Default für MOVE ist "T" (stilles Überschreiben eines bereits vorhandenen Ziels) — ohne
     * diesen Header würde ein Umbenennen auf einen bestehenden Namen die dortige Datei kommentarlos
     * ersetzen statt (wie die UI das erwartet) mit 412 Precondition Failed abzulehnen. */
    suspend fun move(account: WebDavAccount, fromPath: String, toPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = authorizedRequest(account, urlFor(account, fromPath))
                    .header("Destination", urlFor(account, toPath).toString())
                    .header("Overwrite", "F")
                    .method("MOVE", null)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val message = if (response.code == 412) "Ziel existiert bereits" else "HTTP ${response.code}"
                        throw IOException(message)
                    }
                }
            }
        }

    /** Für den "Verbindung testen"-Button im Server-Dialog — Depth:0 fragt nur die Wurzel selbst
     * ab, kein rekursiver Listing-Aufwand nur um Zugangsdaten zu prüfen. */
    suspend fun testConnection(account: WebDavAccount): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authorizedRequest(account, baseHttpUrl(account))
                .method("PROPFIND", propfindBody.toRequestBody("application/xml".toMediaType()))
                .header("Depth", "0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.message}")
            }
        }
    }

    private fun authorizedRequest(account: WebDavAccount, url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("Authorization", Credentials.basic(account.username, account.password))

    private fun baseHttpUrl(account: WebDavAccount): HttpUrl = account.baseUrl.trimEnd('/').toHttpUrl()

    private fun urlFor(account: WebDavAccount, path: String): HttpUrl {
        val builder = baseHttpUrl(account).newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    /**
     * Parst eine WebDAV-`multistatus`-Antwort. Namespace-bewusstes Parsen (`isNamespaceAware`),
     * damit unterschiedliche Server-Präfixe für den `DAV:`-Namespace ("d:", "D:", ganz ohne
     * Präfix) keine Rolle spielen — [XmlPullParser.getName] liefert dann immer nur den lokalen
     * Tag-Namen ohne Präfix.
     */
    private fun parseMultiStatus(xml: String, basePathPrefix: String, requestedUrl: HttpUrl): List<WebDavEntry> {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(xml))

        val decodedPrefix = Uri.decode(basePathPrefix).trimEnd('/')
        val requestedPath = Uri.decode(requestedUrl.encodedPath).trimEnd('/')

        val entries = mutableListOf<WebDavEntry>()
        var href: String? = null
        var isCollection = false
        var sizeBytes = 0L
        var lastModifiedMillis = 0L
        var text = StringBuilder()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    if (parser.name == "response") {
                        href = null; isCollection = false; sizeBytes = 0L; lastModifiedMillis = 0L
                    } else if (parser.name == "collection") {
                        isCollection = true
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "href" -> href = text.toString().trim()
                    "getcontentlength" -> sizeBytes = text.toString().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> lastModifiedMillis = parseHttpDate(text.toString().trim())
                    "response" -> {
                        val entry = href?.let { toEntry(it, decodedPrefix, requestedPath, isCollection, sizeBytes, lastModifiedMillis) }
                        if (entry != null) entries += entry
                    }
                }
            }
            eventType = parser.next()
        }
        return entries
    }

    private fun toEntry(
        rawHref: String,
        basePathPrefix: String,
        requestedPath: String,
        isCollection: Boolean,
        sizeBytes: Long,
        lastModifiedMillis: Long,
    ): WebDavEntry? {
        val decoded = Uri.decode(hrefToEncodedPath(rawHref).substringBefore('?')).trimEnd('/')
        // PROPFIND Depth:1 liefert den angefragten Ordner selbst als erste <response> mit — das
        // ist kein Kind-Eintrag und muss herausgefiltert werden, sonst erschiene der Ordner als
        // Eintrag in seiner eigenen Auflistung.
        if (decoded == requestedPath) return null
        val relative = decoded.removePrefix(basePathPrefix).ifEmpty { "/" }
        val name = relative.substringAfterLast('/')
        if (name.isEmpty()) return null
        return WebDavEntry(
            name = name,
            path = relative,
            isDirectory = isCollection,
            sizeBytes = sizeBytes,
            lastModifiedMillis = lastModifiedMillis,
        )
    }

    /** Server liefern `href` mal als reinen Pfad, mal als vollständige absolute URL (z. B.
     * Nextcloud gibt `https://host/remote.php/dav/files/...` zurück). Nur der Pfadanteil ist mit
     * [basePathPrefix]/[requestedPath] vergleichbar — ohne diese Normalisierung bliebe nach dem
     * `removePrefix()` in [toEntry] die komplette URL als (falscher) "relativer" Pfad übrig, und
     * jeder Folgerequest auf diesen Eintrag (Download/Löschen/Umbenennen über [urlFor]) würde
     * kaputte Pfadsegmente erzeugen. */
    private fun hrefToEncodedPath(rawHref: String): String {
        val trimmed = rawHref.trim()
        val isAbsoluteUrl = trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
        return if (isAbsoluteUrl) Uri.parse(trimmed).encodedPath.orEmpty() else trimmed
    }

    private fun parseHttpDate(text: String): Long =
        runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrDefault(0L)
}
