package de.ble1st.warden.domain.pin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WardenPinReplayAnchorCodecTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val hash = ByteArray(32) { i -> i.toByte() }
        val encoded = WardenPinReplayAnchorCodec.encode(counter = 99L, hash = hash)
        val (counter, decodedHash) = WardenPinReplayAnchorCodec.decode(encoded)
        assertEquals(99L, counter)
        assertArrayEquals(hash, decodedHash)
    }
}
