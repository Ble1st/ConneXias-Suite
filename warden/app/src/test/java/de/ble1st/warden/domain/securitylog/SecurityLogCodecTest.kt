package de.ble1st.warden.domain.securitylog

import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityLogCodecTest {

    private val separator = SecurityLogCodec.FIELD_SEPARATOR

    private val records = listOf(
        SecurityLogRecord(1_000L, SecurityLogEventType.ADB_SHELL_KOMMANDO, "pm uninstall de.ble1st.warden"),
        SecurityLogRecord(2_000L, SecurityLogEventType.PAKET_INSTALLIERT, "com.example.app"),
    )

    @Test
    fun roundTripKeepsAllFields() {
        val decoded = SecurityLogCodec.decode(SecurityLogCodec.encode(records))
        assertEquals(records, decoded.records)
        assertEquals(0, decoded.skippedLines)
    }

    @Test
    fun emptyInputDecodesToNothing() {
        assertEquals(0, SecurityLogCodec.decode("").records.size)
        assertEquals(0, SecurityLogCodec.decode("   ").skippedLines)
    }

    /** Eine kaputte Zeile darf die übrigen nicht mitreißen — und sie muss zählbar bleiben. */
    @Test
    fun brokenLinesAreSkippedAndCounted() {
        val unknownType = "42" + separator + "UNBEKANNTER_TYP" + separator + "x"
        val raw = SecurityLogCodec.encode(records) + "\nkaputt\n" + unknownType
        val decoded = SecurityLogCodec.decode(raw)
        assertEquals(records, decoded.records)
        assertEquals(2, decoded.skippedLines)
    }

    /** Trennzeichen in der Nutzlast dürfen die Zeilenstruktur nicht sprengen. */
    @Test
    fun separatorsInsideDetailDoNotBreakTheLine() {
        val nasty = listOf(
            SecurityLogRecord(5L, SecurityLogEventType.SONSTIGES, "a" + separator + "b\nc"),
        )
        val decoded = SecurityLogCodec.decode(SecurityLogCodec.encode(nasty))
        assertEquals(1, decoded.records.size)
        assertEquals(0, decoded.skippedLines)
        assertEquals("a b c", decoded.records.single().detail)
    }
}
