package de.ble1st.warden.domain.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirTagLikeAdvertisementDecisionTest {

    @Test
    fun matchesFindMyShapedApplePayload() {
        val payload = byteArrayOf(0x12, 0x19, 0x00, 0x01, 0x02)
        assertTrue(AirTagLikeAdvertisementDecision.isFindMyShaped(0x004C, payload))
    }

    @Test
    fun nonAppleManufacturerIdNeverMatches() {
        val payload = byteArrayOf(0x12, 0x19, 0x00)
        assertFalse(AirTagLikeAdvertisementDecision.isFindMyShaped(0x0006, payload))
    }

    @Test
    fun appleManufacturerWithWrongTypeByteDoesNotMatch() {
        val payload = byteArrayOf(0x10, 0x19, 0x00)
        assertFalse(AirTagLikeAdvertisementDecision.isFindMyShaped(0x004C, payload))
    }

    @Test
    fun appleManufacturerWithWrongLengthByteDoesNotMatch() {
        val payload = byteArrayOf(0x12, 0x05, 0x00)
        assertFalse(AirTagLikeAdvertisementDecision.isFindMyShaped(0x004C, payload))
    }

    @Test
    fun nullOrTooShortPayloadDoesNotMatch() {
        assertFalse(AirTagLikeAdvertisementDecision.isFindMyShaped(0x004C, null))
        assertFalse(AirTagLikeAdvertisementDecision.isFindMyShaped(0x004C, byteArrayOf(0x12)))
    }
}
