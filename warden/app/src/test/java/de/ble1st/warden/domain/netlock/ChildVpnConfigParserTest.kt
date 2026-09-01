package de.ble1st.warden.domain.netlock

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildVpnConfigParserTest {

    private val privateKeyBytes = ByteArray(32) { 0x11 }
    private val peerPublicKeyBytes = ByteArray(32) { 0x22 }
    private val presharedKeyBytes = ByteArray(32) { 0x33 }
    private val privateKeyB64 = Base64.getEncoder().encodeToString(privateKeyBytes)
    private val peerPublicKeyB64 = Base64.getEncoder().encodeToString(peerPublicKeyBytes)
    private val presharedKeyB64 = Base64.getEncoder().encodeToString(presharedKeyBytes)

    @Test
    fun parsesMinimalValidConfig() {
        val text = """
            [Interface]
            PrivateKey = $privateKeyB64
            Address = 10.66.0.2/32

            [Peer]
            PublicKey = $peerPublicKeyB64
            Endpoint = vps.example.com:51820
        """.trimIndent()

        val config = ChildVpnConfigParser.parse(text).getOrThrow()

        assertArrayEquals(privateKeyBytes, config.privateKey)
        assertArrayEquals(peerPublicKeyBytes, config.peerPublicKey)
        assertNull(config.presharedKey)
        assertEquals("10.66.0.2", config.addressIpv4)
        assertEquals(32, config.addressPrefixLength)
        assertNull(config.dnsIpv4)
        assertEquals("vps.example.com", config.endpointHost)
        assertEquals(51820, config.endpointPort)
        assertNull(config.persistentKeepaliveSecs)
    }

    @Test
    fun parsesFullConfigIncludingAddressAndDns() {
        val text = """
            # Beispiel-Kommentar
            [Interface]
            PrivateKey = $privateKeyB64
            Address = 10.66.0.2/32
            DNS = 1.1.1.1

            [Peer]
            PublicKey = $peerPublicKeyB64
            PresharedKey = $presharedKeyB64
            Endpoint = 203.0.113.7:51820
            AllowedIPs = 0.0.0.0/0
            PersistentKeepalive = 25
        """.trimIndent()

        val config = ChildVpnConfigParser.parse(text).getOrThrow()

        assertArrayEquals(presharedKeyBytes, config.presharedKey)
        assertEquals("10.66.0.2", config.addressIpv4)
        assertEquals(32, config.addressPrefixLength)
        assertEquals("1.1.1.1", config.dnsIpv4)
        assertEquals("203.0.113.7", config.endpointHost)
        assertEquals(51820, config.endpointPort)
        assertEquals(25, config.persistentKeepaliveSecs)
    }

    /** Regressionstest zur Root-Ursache vom 2026-09-01: die Adresse aus `[Interface]` MUSS
     * durchgereicht werden — mit einer festen Ersatzadresse verwarf WireGuards Cryptokey-Routing
     * auf der Gegenseite jedes Nutzpaket, s. [ChildVpnConfig]-Klassendoc. */
    @Test
    fun addressPrefixOtherThanHostRouteIsPreserved() {
        val config = ChildVpnConfigParser.parse(configWith(address = "10.66.0.2/24")).getOrThrow()

        assertEquals("10.66.0.2", config.addressIpv4)
        assertEquals(24, config.addressPrefixLength)
    }

    @Test
    fun addressWithoutPrefixDefaultsToHostRoute() {
        val config = ChildVpnConfigParser.parse(configWith(address = "10.66.0.2")).getOrThrow()

        assertEquals("10.66.0.2", config.addressIpv4)
        assertEquals(32, config.addressPrefixLength)
    }

    /** wg-quick erlaubt eine kommaseparierte Liste; Wardens TUN ist IPv4-only, der IPv6-Eintrag
     * wird übersprungen statt als Fehler gewertet. */
    @Test
    fun firstIpv4EntryIsPickedFromDualStackAddressList() {
        val config = ChildVpnConfigParser.parse(configWith(address = "fd42:42::2/128, 10.66.0.2/32")).getOrThrow()

        assertEquals("10.66.0.2", config.addressIpv4)
        assertEquals(32, config.addressPrefixLength)
    }

    @Test
    fun firstIpv4EntryIsPickedFromDnsList() {
        val config = ChildVpnConfigParser.parse(
            configWith(address = "10.66.0.2/32", dns = "fd42:42::1, 10.66.0.1, 9.9.9.9"),
        ).getOrThrow()

        assertEquals("10.66.0.1", config.dnsIpv4)
    }

    @Test
    fun missingAddressFails() {
        val text = """
            [Interface]
            PrivateKey = $privateKeyB64

            [Peer]
            PublicKey = $peerPublicKeyB64
            Endpoint = vps.example.com:51820
        """.trimIndent()

        val result = ChildVpnConfigParser.parse(text)
        val error = (result.exceptionOrNull() as ChildVpnConfigParseException).error
        assertEquals(ChildVpnConfigParseError.MissingAddress, error)
    }

    @Test
    fun malformedAddressFails() {
        val result = ChildVpnConfigParser.parse(configWith(address = "10.66.0.999/32"))
        val error = (result.exceptionOrNull() as ChildVpnConfigParseException).error
        assertTrue(error is ChildVpnConfigParseError.MalformedAddress)
    }

    @Test
    fun ipv6OnlyAddressFails() {
        val result = ChildVpnConfigParser.parse(configWith(address = "fd42:42::2/128"))
        val error = (result.exceptionOrNull() as ChildVpnConfigParseException).error
        assertTrue(error is ChildVpnConfigParseError.MalformedAddress)
    }

    @Test
    fun malformedDnsFails() {
        val result = ChildVpnConfigParser.parse(configWith(address = "10.66.0.2/32", dns = "not-an-address"))
        val error = (result.exceptionOrNull() as ChildVpnConfigParseException).error
        assertTrue(error is ChildVpnConfigParseError.MalformedDns)
    }

    @Test
    fun caseInsensitiveKeysAndSections() {
        val text = """
            [interface]
            privatekey = $privateKeyB64
            ADDRESS = 10.66.0.2/32

            [PEER]
            PUBLICKEY = $peerPublicKeyB64
            ENDPOINT = vps.example.com:51820
        """.trimIndent()

        val config = ChildVpnConfigParser.parse(text).getOrThrow()
        assertArrayEquals(privateKeyBytes, config.privateKey)
        assertEquals("10.66.0.2", config.addressIpv4)
    }

    @Test
    fun ipv6EndpointBracketNotationIsUnwrapped() {
        val text = """
            [Interface]
            PrivateKey = $privateKeyB64
            Address = 10.66.0.2/32

            [Peer]
            PublicKey = $peerPublicKeyB64
            Endpoint = [2001:db8::1]:51820
        """.trimIndent()

        val config = ChildVpnConfigParser.parse(text).getOrThrow()
        assertEquals("2001:db8::1", config.endpointHost)
        assertEquals(51820, config.endpointPort)
    }

    @Test
    fun missingInterfaceSectionFails() {
        val text = """
            [Peer]
            PublicKey = $peerPublicKeyB64
            Endpoint = vps.example.com:51820
        """.trimIndent()

        val result = ChildVpnConfigParser.parse(text)
        assertTrue(result.isFailure)
        val error = (result.exceptionOrNull() as ChildVpnConfigParseException).error
        assertEquals(ChildVpnConfigParseError.MissingInterfaceSection, error)
    }

    @Test
    fun missingPeerSectionFails() {
        val text = """
            [Interface]
            PrivateKey = $privateKeyB64
            Address = 10.66.0.2/32
        """.trimIndent()

        val result = ChildVpnConfigParser.parse(text)
        assertTrue(result.isFailure)
        val error = (result.exceptionOrNull() as ChildVpnConfigParseException).error
        assertEquals(ChildVpnConfigParseError.MissingPeerSection, error)
    }

    @Test
    fun missingPrivateKeyFails() {
        val text = """
            [Interface]
            Address = 10.66.0.2/32

            [Peer]
            PublicKey = $peerPublicKeyB64
            Endpoint = vps.example.com:51820
        """.trimIndent()

        val result = ChildVpnConfigParser.parse(text)
        val error = (result.exceptionOrNull() as ChildVpnConfigParseException).error
        assertEquals(ChildVpnConfigParseError.MissingPrivateKey, error)
    }

    @Test
    fun missingEndpointFails() {
        val text = """
            [Interface]
            PrivateKey = $privateKeyB64
            Address = 10.66.0.2/32

            [Peer]
            PublicKey = $peerPublicKeyB64
        """.trimIndent()

        val result = ChildVpnConfigParser.parse(text)
        val error = (result.exceptionOrNull() as ChildVpnConfigParseException).error
        assertEquals(ChildVpnConfigParseError.MissingEndpoint, error)
    }

    @Test
    fun malformedEndpointFails() {
        val result = ChildVpnConfigParser.parse(configWith(address = "10.66.0.2/32", endpoint = "not-a-valid-endpoint"))
        val error = (result.exceptionOrNull() as ChildVpnConfigParseException).error
        assertTrue(error is ChildVpnConfigParseError.MalformedEndpoint)
    }

    @Test
    fun invalidBase64KeyFails() {
        val text = """
            [Interface]
            PrivateKey = not-valid-base64!!!
            Address = 10.66.0.2/32

            [Peer]
            PublicKey = $peerPublicKeyB64
            Endpoint = vps.example.com:51820
        """.trimIndent()

        val result = ChildVpnConfigParser.parse(text)
        val error = (result.exceptionOrNull() as ChildVpnConfigParseException).error
        assertEquals(ChildVpnConfigParseError.InvalidBase64Key("PrivateKey"), error)
    }

    @Test
    fun wrongKeyLengthFailsViaConfigValidation() {
        val shortKeyB64 = Base64.getEncoder().encodeToString(ByteArray(16))
        val text = """
            [Interface]
            PrivateKey = $shortKeyB64
            Address = 10.66.0.2/32

            [Peer]
            PublicKey = $peerPublicKeyB64
            Endpoint = vps.example.com:51820
        """.trimIndent()

        assertTrue(ChildVpnConfigParser.parse(text).isFailure)
    }

    @Test
    fun invalidPersistentKeepaliveFails() {
        val text = """
            [Interface]
            PrivateKey = $privateKeyB64
            Address = 10.66.0.2/32

            [Peer]
            PublicKey = $peerPublicKeyB64
            Endpoint = vps.example.com:51820
            PersistentKeepalive = not-a-number
        """.trimIndent()

        val result = ChildVpnConfigParser.parse(text)
        val error = (result.exceptionOrNull() as ChildVpnConfigParseException).error
        assertEquals(ChildVpnConfigParseError.InvalidValue("PersistentKeepalive", "not-a-number"), error)
    }

    /** Sonst überall identischer, gültiger Konfigurationstext — hält die Tests oben auf das
     * jeweils geprüfte Feld fokussiert. */
    private fun configWith(
        address: String,
        dns: String? = null,
        endpoint: String = "vps.example.com:51820",
    ): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKeyB64")
        appendLine("Address = $address")
        if (dns != null) appendLine("DNS = $dns")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = $peerPublicKeyB64")
        appendLine("Endpoint = $endpoint")
    }
}
