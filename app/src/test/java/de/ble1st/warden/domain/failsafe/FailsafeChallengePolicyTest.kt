package de.ble1st.warden.domain.failsafe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailsafeChallengePolicyTest {

    @Test
    fun ttlExpiresAfterWindow() {
        val issued = 1_000_000L
        assertFalse(FailsafeChallengeTtl.isExpired(issued, issued + FailsafeChallengeTtl.DEFAULT_TTL_MS))
        assertTrue(FailsafeChallengeTtl.isExpired(issued, issued + FailsafeChallengeTtl.DEFAULT_TTL_MS + 1))
    }

    @Test
    fun clockRollbackIsExpired() {
        assertTrue(FailsafeChallengeTtl.isExpired(issuedAtEpochMs = 2_000L, nowEpochMs = 1_999L))
    }

    @Test
    fun recordRoundTripPreservesChallengeAndTimestamp() {
        val record = FailsafeChallengeRecord(challenge = ByteArray(32) { it.toByte() }, issuedAtEpochMs = 42L)
        val decoded = FailsafeChallengeRecordCodec.decode(FailsafeChallengeRecordCodec.encode(record))
        assertEquals(record, decoded)
    }

    @Test
    fun legacyRawChallengeIsTreatedAsAlreadyExpired() {
        val legacy = ByteArray(32) { 7 }
        val decoded = FailsafeChallengeRecordCodec.decode(legacy)
        assertArrayEquals(legacy, decoded.challenge)
        assertEquals(0L, decoded.issuedAtEpochMs)
        assertTrue(FailsafeChallengeTtl.isExpired(decoded.issuedAtEpochMs, nowEpochMs = 1L))
    }

    @Test
    fun deviceCredentialMatchesHighComplexityAtSixteen() {
        assertFalse(FailsafeDeviceCredentialPolicy.isAcceptable(""))
        assertFalse(FailsafeDeviceCredentialPolicy.isAcceptable("123456"))
        assertFalse(FailsafeDeviceCredentialPolicy.isAcceptable("1234567890123456"))
        assertFalse(FailsafeDeviceCredentialPolicy.isAcceptable("0000000000000000"))
        assertFalse(FailsafeDeviceCredentialPolicy.isAcceptable("aaaaaaaaaaaaaaaa"))
        assertTrue(FailsafeDeviceCredentialPolicy.isAcceptable("1503948271639482"))
        assertTrue(FailsafeDeviceCredentialPolicy.isAcceptable("correct-horse-battery"))
        assertTrue(FailsafeDeviceCredentialPolicy.isRepeatingOrOrderedDigits("2468"))
        assertFalse(FailsafeDeviceCredentialPolicy.isRepeatingOrOrderedDigits("1503"))
        assertTrue(FailsafeDeviceCredentialPolicy.isWeakDigitPin("1234567890123456"))
        assertFalse(FailsafeDeviceCredentialPolicy.isWeakDigitPin("1503948271639482"))
    }
}
