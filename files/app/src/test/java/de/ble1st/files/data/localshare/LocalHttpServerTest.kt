package de.ble1st.files.data.localshare

import java.io.File
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Sicherheitstest für [LocalHttpServer] — der Path-Traversal-Schutz (kanonischer Pfad muss
 * unterhalb von rootDir liegen) und die Token-Pflicht sind die einzige Barriere zwischen einem
 * Netzwerk-Client und dem app-eigenen Speicher. Ein Regressionstest hier fängt eine versehentliche
 * Aufweichung dieser Prüfung sofort auf, statt erst im manuellen Probe-Teilen auf einem echten
 * Gerät aufzufallen.
 *
 * Reines Java (keine Android-Abhängigkeit) — deshalb `src/test`, nicht `src/androidTest`. Der
 * Server lauscht auf Loopback, das in [isLocalNetworkIp] ausdrücklich erlaubt ist.
 */
class LocalHttpServerTest {

    private lateinit var rootDir: File
    private lateinit var server: LocalHttpServer
    private val token = "testtoken123"
    private var nextPort = 0

    @Before
    fun setUp() {
        rootDir = File.createTempFile("localshare", "").apply { delete(); mkdirs() }
        File(rootDir, "ok.txt").writeText("hello")
        File(rootDir, "sub").mkdirs()
        File(rootDir, "sub/inner.txt").writeText("inner-content")
        server = LocalHttpServer(rootDir, token)
        server.start()
        nextPort = server.port
    }

    @After
    fun tearDown() {
        server.stop()
        rootDir.deleteRecursively()
    }

    /** Sendet einen rohen GET-Request und liefert (statusLine, body). */
    private fun rawGet(path: String, withToken: Boolean = true): Pair<String, String> {
        Socket("127.0.0.1", nextPort).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            val target = if (withToken) "$path?token=$token" else path
            out.write("GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val raw = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readText()
            // Status-Zeile ist die erste Zeile, Body nach der ersten Leerzeile (Header-/Body-Trennung).
            val statusLine = raw.substringBefore("\r\n")
            val body = raw.substringAfter("\r\n\r\n")
            return statusLine to body
        }
    }

    @Test
    fun validFileWithToken_isServed() {
        val (status, body) = rawGet("/ok.txt")
        assertTrue("expected 200, got $status", status.startsWith("HTTP/1.1 200"))
        assertEquals("hello", body)
    }

    @Test
    fun validNestedFileWithToken_isServed() {
        val (status, body) = rawGet("/sub/inner.txt")
        assertTrue("expected 200, got $status", status.startsWith("HTTP/1.1 200"))
        assertEquals("inner-content", body)
    }

    @Test
    fun requestWithoutToken_isForbidden() {
        val (status, _) = rawGet("/ok.txt", withToken = false)
        assertTrue("expected 403 without token, got $status", status.startsWith("HTTP/1.1 403"))
    }

    @Test
    fun pathTraversalParent_isForbidden() {
        // "../../" versucht, oberhalb von rootDir zu entkommen — kanonische Pfad-Prüfung muss
        // das abweisen, gleich welches Ziel existiert.
        val (status, _) = rawGet("/../../../../etc/hosts")
        assertTrue("expected 403 for traversal, got $status", status.startsWith("HTTP/1.1 403"))
    }

    @Test
    fun pathTraversalEncoded_isForbidden() {
        val (status, _) = rawGet("/%2e%2e%2f%2e%2e%2fetc%2fhosts")
        assertTrue("expected 403 for encoded traversal, got $status", status.startsWith("HTTP/1.1 403"))
    }

    @Test
    fun missingFile_is404() {
        val (status, _) = rawGet("/does-not-exist.txt")
        assertTrue("expected 404, got $status", status.startsWith("HTTP/1.1 404"))
    }

    @Test
    fun nosniffHeader_isPresent() {
        Socket("127.0.0.1", nextPort).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            out.write("GET /ok.txt?token=$token HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val raw = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readText()
            val headers = raw.substringAfter("\r\n").substringBefore("\r\n\r\n")
            assertTrue(
                "expected nosniff header, headers were:\n$headers",
                headers.contains("X-Content-Type-Options: nosniff", ignoreCase = true),
            )
        }
    }
}
