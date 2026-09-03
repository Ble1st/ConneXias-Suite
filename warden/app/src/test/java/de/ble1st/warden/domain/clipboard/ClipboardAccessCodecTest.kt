package de.ble1st.warden.domain.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardAccessCodecTest {

    @Test
    fun emptyListRoundTripsToEmptyDecodeResult() {
        val result = ClipboardAccessCodec.decode(ClipboardAccessCodec.encode(emptyList()))
        assertEquals(emptyList<ClipboardAccessEvent>(), result.events)
        assertEquals(0, result.skippedLines)
    }

    @Test
    fun singleEventRoundTrips() {
        val event = ClipboardAccessEvent(1_700_000_000_000L, "com.example.foreign", "Foreign App", "hello world")
        val decoded = ClipboardAccessCodec.decode(ClipboardAccessCodec.encode(listOf(event)))
        assertEquals(listOf(event), decoded.events)
        assertEquals(0, decoded.skippedLines)
    }

    @Test
    fun textContainingSeparatorAndNewlineRoundTripsExactly() {
        val text = "line one\nline two${ClipboardAccessCodec.FIELD_SEPARATOR}with separator"
        val event = ClipboardAccessEvent(1L, "pkg", "Label", text)
        val decoded = ClipboardAccessCodec.decode(ClipboardAccessCodec.encode(listOf(event)))
        assertEquals(listOf(event), decoded.events)
    }

    @Test
    fun multipleEventsPreserveOrder() {
        val events = listOf(
            ClipboardAccessEvent(1L, "pkg.a", "A", "first"),
            ClipboardAccessEvent(2L, "pkg.b", "B", "second"),
        )
        val decoded = ClipboardAccessCodec.decode(ClipboardAccessCodec.encode(events))
        assertEquals(events, decoded.events)
    }

    @Test
    fun malformedLineIsSkippedNotFatal() {
        val good = ClipboardAccessEvent(1L, "pkg", "Label", "text")
        val raw = ClipboardAccessCodec.encode(listOf(good)) + "\n" + "not enough fields"
        val decoded = ClipboardAccessCodec.decode(raw)
        assertEquals(listOf(good), decoded.events)
        assertEquals(1, decoded.skippedLines)
    }
}
