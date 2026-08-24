package de.ble1st.warden.domain.pin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WardenPinBlobCodecTest {

    private val sample = WardenPinBlob(
        counter = 42L,
        previousHash = ByteArray(32) { it.toByte() },
        locked = true,
        failedAttempts = 3,
        backoffUntilEpochSeconds = 1_700_000_000L,
        pinHash = "\$argon2id\$v=19\$m=19456,t=2,p=1\$c2FsdA\$aGFzaA",
    )

    @Test
    fun encodeThenDecodeRoundTrips() {
        val decoded = WardenPinBlobCodec.decode(WardenPinBlobCodec.encode(sample))

        assertEquals(sample, decoded)
    }

    @Test
    fun genesisRoundTrips() {
        val genesis = WardenPinBlob.genesis()

        assertEquals(genesis, WardenPinBlobCodec.decode(WardenPinBlobCodec.encode(genesis)))
    }

    @Test
    fun encodingIsDeterministic() {
        val a = WardenPinBlobCodec.encode(sample)
        val b = WardenPinBlobCodec.encode(sample.copy())

        assertArrayEquals(a, b)
    }

    @Test
    fun sameLogicalBlobHashesIdentically() {
        assertArrayEquals(WardenPinBlobCodec.hashOf(sample), WardenPinBlobCodec.hashOf(sample.copy()))
    }

    @Test
    fun differentBlobsHashDifferently() {
        val other = sample.copy(counter = sample.counter + 1)

        assertThrows(AssertionError::class.java) {
            assertArrayEquals(WardenPinBlobCodec.hashOf(sample), WardenPinBlobCodec.hashOf(other))
        }
    }

    @Test
    fun decodeRejectsTruncatedData() {
        val truncated = WardenPinBlobCodec.encode(sample).copyOf(5)

        assertThrows(Exception::class.java) { WardenPinBlobCodec.decode(truncated) }
    }

    @Test
    fun decodeRejectsTrailingGarbage() {
        val withGarbage = WardenPinBlobCodec.encode(sample) + byteArrayOf(0x00)

        assertThrows(IllegalArgumentException::class.java) { WardenPinBlobCodec.decode(withGarbage) }
    }

    @Test
    fun decodeRejectsWrongArrayLength() {
        val encoded = WardenPinBlobCodec.encode(sample)
        val tampered = encoded.copyOf()
        tampered[0] = 0x85.toByte() // Array-Header für 5 statt 6 Felder

        assertThrows(IllegalArgumentException::class.java) { WardenPinBlobCodec.decode(tampered) }
    }

    @Test
    fun decodeRejectsFlippedBoolByte() {
        val encoded = WardenPinBlobCodec.encode(sample)
        // Position des locked-Feldes: Array-Header (1 Byte) + counter=42 als uint8-Form (2 Byte,
        // da 42 >= 24) + previousHash-Header für 32 Byte (2 Byte, da 32 >= 24) + 32 Byte Hash.
        val boolIndex = 1 + 2 + 2 + 32
        val tampered = encoded.copyOf()
        tampered[boolIndex] = 0x00 // weder 0xf4 noch 0xf5

        assertThrows(IllegalArgumentException::class.java) { WardenPinBlobCodec.decode(tampered) }
    }

    @Test
    fun encodeRejectsWrongHashLength() {
        assertThrows(IllegalArgumentException::class.java) {
            sample.copy(previousHash = ByteArray(16))
        }
    }
}
