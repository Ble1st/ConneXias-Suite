package de.ble1st.warden.netlock

import de.ble1st.warden.domain.netlock.ChildVpnConfig
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Deckt ausschließlich die Binärkodierung ab ([encodeConfig]/[decodeConfig]) — [ChildVpnConfigStore]
 * selbst besteht darüber hinaus nur aus Delegation an [de.ble1st.warden.crypto.EnvelopeFile] und
 * bräuchte dafür Android-Keystore, also einen Instrumentierungstest ohne zusätzlichen Erkenntniswert.
 */
class ChildVpnConfigStoreTest {

    private val config = ChildVpnConfig(
        privateKey = ByteArray(32) { 0x11 },
        peerPublicKey = ByteArray(32) { 0x22 },
        presharedKey = ByteArray(32) { 0x33 },
        addressIpv4 = "10.66.0.2",
        addressPrefixLength = 32,
        dnsIpv4 = "10.66.0.1",
        endpointHost = "vps.example.com",
        endpointPort = 51820,
        persistentKeepaliveSecs = 25,
    )

    @Test
    fun roundTripPreservesAllFields() {
        val decoded = decodeConfig(encodeConfig(config))

        assertArrayEquals(config.privateKey, decoded.privateKey)
        assertArrayEquals(config.peerPublicKey, decoded.peerPublicKey)
        assertArrayEquals(config.presharedKey, decoded.presharedKey)
        assertEquals(config.addressIpv4, decoded.addressIpv4)
        assertEquals(config.addressPrefixLength, decoded.addressPrefixLength)
        assertEquals(config.dnsIpv4, decoded.dnsIpv4)
        assertEquals(config.endpointHost, decoded.endpointHost)
        assertEquals(config.endpointPort, decoded.endpointPort)
        assertEquals(config.persistentKeepaliveSecs, decoded.persistentKeepaliveSecs)
    }

    @Test
    fun roundTripPreservesAbsentOptionalFields() {
        val minimal = config.copy(presharedKey = null, dnsIpv4 = null, persistentKeepaliveSecs = null)

        val decoded = decodeConfig(encodeConfig(minimal))

        assertNull(decoded.presharedKey)
        assertNull(decoded.dnsIpv4)
        assertNull(decoded.persistentKeepaliveSecs)
        assertEquals("10.66.0.2", decoded.addressIpv4)
    }

    /** Ein v1-Satz (vor dem 2026-09-01, ohne Versionspräfix und ohne `Address`) darf NICHT als
     * gültige Konfiguration durchgehen — er beginnt mit der Schlüssellänge 32 statt der
     * Formatversion. Ohne die Adresse ist keine Migration möglich, der Nutzer muss die
     * WireGuard-Konfiguration erneut einlesen; entscheidend ist, dass das laut Klassendoc als
     * Exception sichtbar wird statt als stilles "nie konfiguriert". */
    @Test
    fun legacyFormatWithoutVersionPrefixIsRejected() {
        val legacy = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use { data ->
                data.writeInt(32)
                data.write(ByteArray(32) { 0x11 })
                data.writeInt(32)
                data.write(ByteArray(32) { 0x22 })
                data.writeBoolean(false)
                data.writeUTF("vps.example.com")
                data.writeInt(51820)
                data.writeBoolean(false)
            }
        }.toByteArray()

        assertThrows(IllegalStateException::class.java) { decodeConfig(legacy) }
    }
}
