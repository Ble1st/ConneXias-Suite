package de.ble1st.files.data.share

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * „Teilen mit ConneXias Files" (`ACTION_SEND`/`ACTION_SEND_MULTIPLE`). Braucht Instrumentation,
 * weil `Intent.getParcelableExtra` echte Parcel-Serialisierung ist.
 */
@RunWith(AndroidJUnit4::class)
class IncomingShareInstrumentedTest {

    private val first: Uri = Uri.parse("content://de.ble1st.test/1")
    private val second: Uri = Uri.parse("content://de.ble1st.test/2")

    @Before
    @After
    fun reset() {
        IncomingShare.consume()
    }

    @Test
    fun singleSendIsAccepted() {
        IncomingShare.setFromIntent(
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, first) },
        )
        assertEquals(listOf(first), IncomingShare.pending.value)
    }

    @Test
    fun multipleSendIsAccepted() {
        IncomingShare.setFromIntent(
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
            },
        )
        assertEquals(listOf(first, second), IncomingShare.pending.value)
    }

    @Test
    fun unrelatedActionIsIgnored() {
        IncomingShare.setFromIntent(
            Intent(Intent.ACTION_VIEW).apply { putExtra(Intent.EXTRA_STREAM, first) },
        )
        assertNull(IncomingShare.pending.value)
    }

    @Test
    fun sendWithoutStreamExtraIsIgnored() {
        IncomingShare.setFromIntent(Intent(Intent.ACTION_SEND).apply { type = "text/plain" })
        assertNull(IncomingShare.pending.value)
    }

    @Test
    fun consumeClearsThePendingList() {
        IncomingShare.setFromIntent(
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, first) },
        )
        assertEquals(listOf(first), IncomingShare.consume())
        assertNull(IncomingShare.pending.value)
        assertTrue(IncomingShare.consume().isEmpty())
    }

    @Test
    fun anIgnoredIntentDoesNotClearAnAlreadyPendingShare() {
        // setFromIntent überschreibt bewusst nur bei nicht-leerem Ergebnis: der Nutzer soll die
        // geteilten Dateien nicht dadurch verlieren, dass die Activity zwischendurch einen
        // anderen Intent bekommt (s. IncomingShare-Klassendoc).
        IncomingShare.setFromIntent(
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, first) },
        )
        IncomingShare.setFromIntent(Intent(Intent.ACTION_MAIN))
        assertEquals(listOf(first), IncomingShare.pending.value)
    }
}
