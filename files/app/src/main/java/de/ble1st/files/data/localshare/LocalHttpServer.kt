package de.ble1st.files.data.localshare

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimaler, selbstgeschriebener HTTP/1.1-Server für die WLAN/Hotspot-Freigabe eines einzelnen
 * Ordners — statt einer Bibliothek wie `nanohttpd` (seit 2016 unmaintained, zuletzt am 2026-09-03
 * geprüft) über eine eigene, kleine Implementierung, dieselbe Haltung wie schon beim eigenen
 * OkHttp-basierten WebDAV-Client (s. `data/webdav/WebDavClient.kt`-Klassendoc). Der tatsächliche
 * Funktionsumfang ist klein genug, um ihn selbst zu halten: nur `GET`, keine Uploads, kein
 * Keep-Alive (jede Verbindung wird nach genau einer Antwort geschlossen) — für "Ordner im
 * Heimnetz kurz zum Herunterladen freigeben" reicht das, s. README für die bewusst ausgesparten
 * Ausbauschritte (Upload-Empfang, HTTPS).
 *
 * Token-Pflicht statt eines völlig offenen Servers: jeder im selben WLAN/Hotspot könnte sonst den
 * freigegebenen Ordner erraten/scannen und mitlesen, solange die Freigabe aktiv ist — das Token
 * steht nur in der Freigabe-URL, die der Nutzer selbst per Kopieren/Teilen weitergibt.
 *
 * **Härtung:** der Server lehnt Verbindungen von öffentlich routbaren IP-Adressen ab
 * ([isLocalNetworkIp]) — ein Nutzer, der versehentlich in einem öffentlichen WLAN teilt, würde
 * sonst dem ganzen Netz ausgesetzt sein. Das ist eine Heuristik (RFC1918/loopback/link-local),
 * kein echter Schutz gegen ARP-Spoofing im selben Netz, aber sie verhindert den einfachsten
 * Fremd-Zugriff. Zusätzlich: begrenzter Thread-Pool (DoS-Schutz), `X-Content-Type-Options: nosniff`
 * gegen MIME-Sniffing und vollständiges HTML-Escaping (inkl. einfacher Anführungszeichen).
 */
class LocalHttpServer(private val rootDir: File, private val token: String) {

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private var executor: ExecutorService? = null

    @Volatile
    var port: Int = 0
        private set

    /** Begrenzter Thread-Pool statt newCachedThreadPool — ein unbegrenzter Pool wäre durch eine
     *  einfache `while true; do curl …; done`-Schleife von einem einzelnen Host ressourcenerschöpfend
     *  (DoS). 8 parallele Verbindungen decken jedes realistische Heimnetz-Szenario. */
    private val maxConcurrentConnections: Int = 8

    /** Port 0 lässt das Betriebssystem einen freien Port wählen — eine feste Portnummer könnte
     * bereits belegt sein (z. B. von einer anderen App oder einem vorherigen, noch nicht ganz
     * geschlossenen Lauf dieses Servers). */
    @Throws(IOException::class)
    fun start() {
        val socket = ServerSocket(0)
        serverSocket = socket
        port = socket.localPort
        running.set(true)
        val pool = Executors.newFixedThreadPool(maxConcurrentConnections)
        executor = pool
        pool.execute {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break // socket.close() aus stop() landet hier als IOException — normales Ende
                }
                pool.execute { handleClient(client) }
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        executor?.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            // Nur Verbindungen aus lokalen Netzwerken zulassen — öffentliche IPs (z. B. wenn der
            // Nutzer versehentlich in einem öffentlichen WLAN teilt) werden abgewiesen, bevor
            // irgendein Pfad/Token ausgewertet wird. Loopback (Tests/lokaler Zugriff) und
            // RFC1918/link-local sind erlaubt.
            if (!isLocalNetworkIp(socket.inetAddress)) {
                runCatching { socket.close() }
                return
            }
            runCatching {
                socket.soTimeout = 10_000
                val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = input.readLine() ?: return
                val requestParts = requestLine.split(" ")
                if (requestParts.size < 2) return
                // Header werden nicht ausgewertet, müssen aber bis zur Leerzeile konsumiert werden —
                // sonst bleiben sie im Socket-Puffer stehen und ein Client, der auf dieselbe
                // Verbindung eine zweite Anfrage schickt, bekäme die Antwort auf die falsche Anfrage.
                // Praktisch harmlos, weil hier ohnehin nach jeder Antwort geschlossen wird, aber
                // korrektes Protokollverhalten kostet nichts.
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                }
                if (requestParts[0] != "GET") {
                    writeResponse(socket, 405, "Method Not Allowed", "text/plain; charset=utf-8", "Nur GET wird unterstützt.")
                    return
                }
                handleGet(socket, requestParts[1])
            }
        }
    }

    private fun handleGet(client: Socket, rawTarget: String) {
        val questionMark = rawTarget.indexOf('?')
        val rawPath = if (questionMark >= 0) rawTarget.substring(0, questionMark) else rawTarget
        val rawQuery = if (questionMark >= 0) rawTarget.substring(questionMark + 1) else ""
        val query = parseQuery(rawQuery)

        if (query["token"] != token) {
            writeResponse(client, 403, "Forbidden", "text/plain; charset=utf-8", "Ungültiges oder fehlendes Token.")
            return
        }

        val decodedPath = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrNull()?.removePrefix("/")
        if (decodedPath == null) {
            writeResponse(client, 400, "Bad Request", "text/plain; charset=utf-8", "Ungültiger Pfad.")
            return
        }

        // Path-Traversal-Schutz gegen einen von außen (Netzwerk statt lokaler UI-Eingabe!) kommenden
        // Pfad wie "../../data/data/de.ble1st.files/..." — kanonischer Zielpfad muss unterhalb von
        // rootDir liegen, dieselbe Prüfung wie FileOperations.isSameOrDescendant, hier aber gegen
        // einen nicht vertrauenswürdigen Netzwerk-Client statt einer lokalen Nutzereingabe.
        val rootCanonical = rootDir.canonicalFile
        val target = File(rootDir, decodedPath)
        val targetCanonical = runCatching { target.canonicalFile }.getOrNull()
        if (targetCanonical == null ||
            (targetCanonical != rootCanonical && !targetCanonical.path.startsWith(rootCanonical.path + File.separator))
        ) {
            writeResponse(client, 403, "Forbidden", "text/plain; charset=utf-8", "Zugriff verweigert.")
            return
        }
        if (!targetCanonical.exists()) {
            writeResponse(client, 404, "Not Found", "text/plain; charset=utf-8", "Nicht gefunden.")
            return
        }

        if (targetCanonical.isDirectory) {
            writeResponse(client, 200, "OK", "text/html; charset=utf-8", renderDirectoryListing(targetCanonical, decodedPath))
        } else {
            streamFile(client, targetCanonical)
        }
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isEmpty()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val equalsIndex = pair.indexOf('=')
            if (equalsIndex < 0) return@mapNotNull null
            val key = runCatching { URLDecoder.decode(pair.substring(0, equalsIndex), "UTF-8") }.getOrNull() ?: return@mapNotNull null
            val value = runCatching { URLDecoder.decode(pair.substring(equalsIndex + 1), "UTF-8") }.getOrNull() ?: return@mapNotNull null
            key to value
        }.toMap()
    }

    private fun renderDirectoryListing(dir: File, relativePath: String): String {
        val entries = dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
        val rows = entries.joinToString("\n") { entry ->
            val childRelative = if (relativePath.isEmpty()) entry.name else "$relativePath/${entry.name}"
            val href = URLEncoder.encode(childRelative, "UTF-8").replace("+", "%20") + "?token=" +
                URLEncoder.encode(token, "UTF-8")
            val label = htmlEscape(entry.name) + if (entry.isDirectory) "/" else ""
            "<li><a href=\"/$href\">$label</a></li>"
        }
        val title = htmlEscape(if (relativePath.isEmpty()) "/" else "/$relativePath")
        return """
            |<!doctype html><html><head><meta charset="utf-8"><title>$title</title></head>
            |<body><h1>$title</h1><ul>$rows</ul></body></html>
        """.trimMargin()
    }

    private fun streamFile(client: Socket, file: File) {
        val contentType = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
        val output = client.getOutputStream()
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Content-Length: ${file.length()}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        file.inputStream().use { it.copyTo(output) }
        output.flush()
    }

    private fun writeResponse(client: Socket, statusCode: Int, statusText: String, contentType: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val output = client.getOutputStream()
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
            "Content-Type: $contentType\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.write(bodyBytes)
        output.flush()
    }

    /** Liefert true für Loopback-, RFC1918- und link-local-Adressen — die einzigen Adressen, die
     *  bei einer Heimnetz-/Hotspot-Freigabe legitim sind. Öffentliche IPs werden abgewiesen, damit
     *  ein versehentliches Teilen in einem öffentlichen WLAN nicht dem ganzen Netz aussetzt. */
    private fun isLocalNetworkIp(address: InetAddress): Boolean =
        address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress

    private fun htmlEscape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
