package de.ble1st.files.data.share

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * „Öffnen mit ConneXias Files" (`ACTION_VIEW`). [IncomingView.copyToCache] braucht einen echten
 * `ContentResolver` und ein echtes `cacheDir` — beides gibt es nur unter Instrumentation.
 *
 * Der interessante Teil ist die Namens-Sanierung: der Anzeigename kommt aus der `DISPLAY_NAME`-
 * Spalte einer **fremden** App und ist damit nicht vertrauenswürdig (dieselbe Lücke wie
 * analyse.md 2-02, Path-Traversal über präparierte Namen).
 */
@RunWith(AndroidJUnit4::class)
class IncomingViewInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var sourceDir: File

    @Before
    fun setUp() {
        sourceDir = File(context.cacheDir, "incoming-view-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        sourceDir.deleteRecursively()
        File(context.cacheDir, "external_view").deleteRecursively()
        IncomingView.consume()
    }

    private fun sourceFile(name: String, content: String = "inhalt"): Uri {
        val file = File(sourceDir, name)
        file.writeText(content)
        return file.toUri()
    }

    @Test
    fun viewIntentIsPickedUp() {
        val uri = sourceFile("bericht.txt")
        IncomingView.setFromIntent(Intent(Intent.ACTION_VIEW, uri))
        assertEquals(uri, IncomingView.pending.value)
    }

    @Test
    fun unrelatedActionIsIgnored() {
        IncomingView.setFromIntent(Intent(Intent.ACTION_SEND, sourceFile("a.txt")))
        assertNull(IncomingView.pending.value)
    }

    @Test
    fun viewIntentWithoutDataIsIgnored() {
        IncomingView.setFromIntent(Intent(Intent.ACTION_VIEW))
        assertNull(IncomingView.pending.value)
    }

    @Test
    fun consumeClearsPending() {
        IncomingView.setFromIntent(Intent(Intent.ACTION_VIEW, sourceFile("a.txt")))
        assertNotNull(IncomingView.consume())
        assertNull(IncomingView.pending.value)
    }

    @Test
    fun copyLandsInAFreshCacheDirectoryWithTheContent() {
        val dir = requireNotNull(IncomingView.copyToCache(context, sourceFile("bericht.txt", "hallo")))
        val copied = requireNotNull(dir.listFiles()).single()
        assertEquals("bericht.txt", copied.name)
        assertEquals("hallo", copied.readText())
        assertTrue(dir.canonicalPath.startsWith(context.cacheDir.canonicalPath))
    }

    @Test
    fun eachCopyGetsItsOwnDirectory() {
        // Zwei Dateien gleichen Namens dürfen sich nicht gegenseitig überschreiben.
        val a = requireNotNull(IncomingView.copyToCache(context, sourceFile("gleich.txt", "eins")))
        val b = requireNotNull(IncomingView.copyToCache(context, sourceFile("gleich.txt", "zwei")))
        assertTrue(a.canonicalPath != b.canonicalPath)
        assertEquals("eins", File(a, "gleich.txt").readText())
        assertEquals("zwei", File(b, "gleich.txt").readText())
    }

    @Test
    fun copiedFileNeverEscapesItsDirectory() {
        // Der eigentliche Angriffsfall: ein Anzeigename mit Traversal-Anteil. Bei einer file://-Uri
        // liefert queryDisplayName den lastPathSegment — der trägt hier den präparierten Namen.
        val evil = Uri.parse("content://de.ble1st.test/x/..%2F..%2Fentfuehrt.txt")
        val dir = IncomingView.copyToCache(context, evil)
        // Entweder abgelehnt (null) oder innerhalb des eigenen Ordners gelandet — nie darüber.
        if (dir != null) {
            val copied = requireNotNull(dir.listFiles()).single()
            assertTrue(copied.canonicalPath.startsWith(dir.canonicalPath + File.separator))
        }
    }

    @Test
    fun unreadableSourceYieldsNullInsteadOfAnEmptyFile() {
        // analyse.md 2-16 in Grün: ein nicht lesbarer Stream darf nicht als leere Datei
        // durchgehen. Hier gibt es die Quelldatei gar nicht.
        val missing = File(sourceDir, "gibtsnicht.txt").toUri()
        assertNull(IncomingView.copyToCache(context, missing))
    }
}
