package de.ble1st.warden.domain.logging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HashChainAnchorCodecTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val hash = ByteArray(32) { i -> i.toByte() }
        val encoded = HashChainAnchorCodec.encode(sequence = 12345L, hash = hash)
        val (sequence, decodedHash) = HashChainAnchorCodec.decode(encoded)
        assertEquals(12345L, sequence)
        assertArrayEquals(hash, decodedHash)
    }

    @Test
    fun encodeDecodeZeroSequence() {
        val hash = ByteArray(32) { 7 }
        val encoded = HashChainAnchorCodec.encode(sequence = 0L, hash = hash)
        val (sequence, decodedHash) = HashChainAnchorCodec.decode(encoded)
        assertEquals(0L, sequence)
        assertArrayEquals(hash, decodedHash)
    }
}
