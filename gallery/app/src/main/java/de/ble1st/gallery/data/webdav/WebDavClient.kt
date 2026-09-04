package de.ble1st.gallery.data.webdav

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
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Schlanker WebDAV-Client auf OkHttp für Cloud-Sync — nur PUT/MKCOL/PROPFIND(Depth:0), kein
 * Listing/Download/Delete/Move wie ConneXias Files' vollwertiger `WebDavClient`: ein
 * Ein-Wege-Backup braucht nur "Ordner anlegen" + "Datei hochladen" + "Verbindung testen"
 * ([de.ble1st.gallery.data.sync.CloudSyncManager] entscheidet lokal per [de.ble1st.gallery.data.sync.CloudSyncState]
 * — statt einer serverseitigen Verzeichnisauflistung — welche Elemente schon gesichert sind, s.
 * dortiger Klassendoc). Bewusst kein Digest-Auth (nur Basic) — dieselbe Abwägung wie ConneXias
 * Files: Basic über HTTPS reicht für die große Mehrheit selbst gehosteter Server.
 */
object WebDavClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val propfindBody = """<?xml version="1.0" encoding="utf-8" ?>
        |<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>
    """.trimMargin()

    suspend fun upload(account: WebDavAccount, path: String, source: File, mimeType: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = authorizedRequest(account, urlFor(account, path))
                    .put(source.asRequestBody(mimeType.toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                }
            }
        }

    /** Legt den Backup-Zielordner an — ein bereits vorhandener Ordner meldet üblicherweise
     * `405 Method Not Allowed`, das ist hier kein Fehlerfall (Aufrufer ignoriert ein
     * fehlgeschlagenes `mkdir` bewusst, s. `CloudSyncManager.sync`). */
    suspend fun mkdir(account: WebDavAccount, path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = authorizedRequest(account, urlFor(account, path)).method("MKCOL", null).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            }
        }
    }

    /** Für den "Verbindung testen"-Button — Depth:0 fragt nur die Wurzel selbst ab, kein
     * rekursiver Listing-Aufwand nur um Zugangsdaten zu prüfen. */
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
}
