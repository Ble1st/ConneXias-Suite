package de.ble1st.warden.domain.netlock

import java.util.Base64

/**
 * ChildVPN (2026-08-31, `docs/design-barbican-prozess-childvpn.md`): geparste Konfiguration für
 * den zweiten, optionalen WireGuard-Tunnel zur eigenen VPS — 1:1 die Felder, die
 * `childvpn.rs::set_child_vpn_config` braucht. Schlüssel liegen als rohe 32-Byte-Arrays vor, nicht
 * als Base64-Text ([ChildVpnConfigParser] decodiert bereits beim Parsen) — dieselbe Entscheidung
 * wie in `childvpn.rs`s eigenem Klassendoc begründet: der Rust-Crate soll keine zusätzliche
 * Base64-Abhängigkeit brauchen.
 *
 * `persistentKeepaliveSecs`/`presharedKey` sind `null`, wenn der eingefügte Konfigurationstext das
 * jeweilige Feld nicht enthält — echtes wg-quick-Verhalten (kein erfundener Default-Wert, s.
 * [ChildVpnConfigParser]-Klassendoc "kein stiller Default").
 *
 * KRITISCH (2026-09-01, Root-Ursache des ChildVPN-Fehlers "verbunden, aber kein Internet"):
 * [addressIpv4]/[addressPrefixLength] stammen aus `[Interface] Address` und wurden bis zu diesem
 * Datum vom Parser bewusst weggeworfen ("ChildVPN ist reines Layer-3-Passthrough, es gibt kein
 * eigenes `wg0`-Interface mit eigener Adresse"). Diese Begründung war falsch: es gibt zwar kein
 * eigenes `wg0`, aber der von [de.ble1st.warden.vpn.WardenVpnService] aufgebaute TUN übernimmt
 * dessen Rolle vollständig — und WireGuards Cryptokey-Routing prüft auf der Gegenseite die
 * QUELL-Adresse jedes entschlüsselten Pakets gegen `AllowedIPs` des Peers. Mit Wardens fest
 * verdrahteter TUN-Adresse (`10.64.0.1`) statt der von der VPS zugewiesenen Adresse verwarf die
 * VPS jedes einzelne Nutzpaket direkt nach dem erfolgreichen Entschlüsseln — Handshake und
 * Keepalives funktionierten, echte Daten kamen nie zurück. Deshalb ist `Address` jetzt ein
 * PFLICHTFELD, s. [ChildVpnConfigParser]-Klassendoc.
 *
 * [dnsIpv4] stammt analog aus dem optionalen `[Interface] DNS` — die VPS betreibt oft einen eigenen
 * Resolver, der nur aus dem Tunnel heraus erreichbar ist; `null` bedeutet "im Konfigurationstext
 * nicht angegeben", der Aufrufer fällt dann auf seinen eigenen Default zurück.
 */
data class ChildVpnConfig(
    val privateKey: ByteArray,
    val peerPublicKey: ByteArray,
    val presharedKey: ByteArray?,
    val addressIpv4: String,
    val addressPrefixLength: Int,
    val dnsIpv4: String?,
    val endpointHost: String,
    val endpointPort: Int,
    val persistentKeepaliveSecs: Int?,
) {
    init {
        require(privateKey.size == KEY_LENGTH_BYTES) { "PrivateKey muss $KEY_LENGTH_BYTES Byte lang sein, war ${privateKey.size}" }
        require(peerPublicKey.size == KEY_LENGTH_BYTES) { "PublicKey muss $KEY_LENGTH_BYTES Byte lang sein, war ${peerPublicKey.size}" }
        require(presharedKey == null || presharedKey.size == KEY_LENGTH_BYTES) {
            "PresharedKey muss $KEY_LENGTH_BYTES Byte lang sein, war ${presharedKey?.size}"
        }
        require(ChildVpnConfigParser.isIpv4(addressIpv4)) { "Address ist keine gültige IPv4-Adresse: $addressIpv4" }
        require(addressPrefixLength in 0..32) { "Address-Präfixlänge außerhalb des gültigen Bereichs: $addressPrefixLength" }
        require(dnsIpv4 == null || ChildVpnConfigParser.isIpv4(dnsIpv4)) { "DNS ist keine gültige IPv4-Adresse: $dnsIpv4" }
        require(endpointHost.isNotBlank()) { "Endpoint-Host darf nicht leer sein" }
        require(endpointPort in 1..65535) { "Endpoint-Port außerhalb des gültigen Bereichs: $endpointPort" }
        require(persistentKeepaliveSecs == null || persistentKeepaliveSecs in 0..65535) {
            "PersistentKeepalive außerhalb des gültigen Bereichs: $persistentKeepaliveSecs"
        }
    }

    private companion object {
        const val KEY_LENGTH_BYTES = 32
    }
}

sealed class ChildVpnConfigParseError {
    data object MissingInterfaceSection : ChildVpnConfigParseError()
    data object MissingPeerSection : ChildVpnConfigParseError()
    data object MissingPrivateKey : ChildVpnConfigParseError()
    data object MissingPeerPublicKey : ChildVpnConfigParseError()
    data object MissingEndpoint : ChildVpnConfigParseError()
    data class MalformedEndpoint(val raw: String) : ChildVpnConfigParseError()

    /** s. [ChildVpnConfig]-Klassendoc (2026-09-01): ohne die von der VPS zugewiesene Adresse
     * verwirft deren Cryptokey-Routing jedes Nutzpaket — ein stiller Default wäre hier fatal. */
    data object MissingAddress : ChildVpnConfigParseError()
    data class MalformedAddress(val raw: String) : ChildVpnConfigParseError()
    data class MalformedDns(val raw: String) : ChildVpnConfigParseError()
    data class InvalidBase64Key(val field: String) : ChildVpnConfigParseError()
    data class InvalidValue(val field: String, val raw: String) : ChildVpnConfigParseError()
}

/**
 * Parst ein wg-quick-Standardkonfigurationstext (das übliche `wg0.conf`-Format) in ein
 * [ChildVpnConfig] — bewusst KEIN eigenes Formular für die VPS-Anbindung (Nutzer-Entscheidung
 * 2026-08-31, s. Design-Dok "Entschieden: Onboarding der VPS-Konfiguration"): jeder WireGuard-
 * Server exportiert dieses Format standardmäßig (z. B. `wg genconfig`/die üblichen VPS-seitigen
 * Setup-Skripte), der Nutzer kopiert/scannt es unverändert.
 *
 * `AllowedIPs` im `[Peer]`-Abschnitt wird bewusst ignoriert (kein Fehler, keine Warnung):
 * "gesamter Traffic" ist die explizite Nutzeranforderung (s. Design-Dok), was die clientseitige
 * `AllowedIPs`-Einschränkung bedeutungslos macht. Kommentarzeilen (`#`/`;`) und Leerzeilen werden
 * übersprungen, Schlüssel/Werte sind case-insensitiv (`PrivateKey`/`privatekey`/`PRIVATEKEY`
 * gleichwertig), wie wg-quick selbst es handhabt.
 *
 * KRITISCH (2026-09-01): `Address` wurde bis zu diesem Datum ebenfalls ignoriert und ist jetzt ein
 * PFLICHTFELD ([ChildVpnConfigParseError.MissingAddress]) — die vollständige Begründung samt
 * Fehlerbild steht im [ChildVpnConfig]-Klassendoc. Kurz: die Gegenseite prüft die Quell-Adresse
 * jedes entschlüsselten Pakets gegen `AllowedIPs` des Peers, eine falsche TUN-Adresse lässt jedes
 * Nutzpaket auf der VPS lautlos fallen. `DNS` wird seitdem als optionales Feld übernommen statt
 * verworfen. Beide Felder dürfen wg-quick-konform eine kommaseparierte Liste sein (typisch
 * `10.66.0.2/32, fd42::2/128`); genommen wird jeweils der erste IPv4-Eintrag — Wardens TUN ist
 * IPv4-only, IPv6-Einträge werden übersprungen, nicht als Fehler gewertet.
 *
 * **Kein stiller Default:** ein im Text fehlendes `PersistentKeepalive`/`PresharedKey` wird als
 * `null` übernommen, nicht als erfundener Wert (z. B. der verbreitete Client-Empfehlungswert 25)
 * — dieselbe Fail-safe-Haltung wie überall im Projekt.
 */
object ChildVpnConfigParser {

    fun parse(text: String): Result<ChildVpnConfig> = runCatching {
        val (interfaceFields, peerFields) = splitSections(text)
            ?: return Result.failure(ChildVpnConfigParseException(ChildVpnConfigParseError.MissingInterfaceSection))
        if (peerFields == null) {
            return Result.failure(ChildVpnConfigParseException(ChildVpnConfigParseError.MissingPeerSection))
        }

        val privateKeyRaw = interfaceFields["privatekey"]
            ?: return Result.failure(ChildVpnConfigParseException(ChildVpnConfigParseError.MissingPrivateKey))
        val privateKey = decodeKey(privateKeyRaw, "PrivateKey").getOrElse { return Result.failure(it) }

        val peerPublicKeyRaw = peerFields["publickey"]
            ?: return Result.failure(ChildVpnConfigParseException(ChildVpnConfigParseError.MissingPeerPublicKey))
        val peerPublicKey = decodeKey(peerPublicKeyRaw, "PublicKey").getOrElse { return Result.failure(it) }

        val presharedKeyRaw = peerFields["presharedkey"]
        val presharedKey = presharedKeyRaw?.let { decodeKey(it, "PresharedKey").getOrElse { e -> return Result.failure(e) } }

        val addressRaw = interfaceFields["address"]
            ?: return Result.failure(ChildVpnConfigParseException(ChildVpnConfigParseError.MissingAddress))
        val (addressIpv4, addressPrefixLength) = parseAddress(addressRaw)
            ?: return Result.failure(ChildVpnConfigParseException(ChildVpnConfigParseError.MalformedAddress(addressRaw)))

        val dnsRaw = interfaceFields["dns"]
        val dnsIpv4 = dnsRaw?.let {
            firstIpv4Entry(it)?.first
                ?: return Result.failure(ChildVpnConfigParseException(ChildVpnConfigParseError.MalformedDns(it)))
        }

        val endpointRaw = peerFields["endpoint"]
            ?: return Result.failure(ChildVpnConfigParseException(ChildVpnConfigParseError.MissingEndpoint))
        val (host, port) = parseEndpoint(endpointRaw)
            ?: return Result.failure(ChildVpnConfigParseException(ChildVpnConfigParseError.MalformedEndpoint(endpointRaw)))

        val persistentKeepalive = peerFields["persistentkeepalive"]?.let {
            it.toIntOrNull() ?: return Result.failure(
                ChildVpnConfigParseException(ChildVpnConfigParseError.InvalidValue("PersistentKeepalive", it)),
            )
        }

        ChildVpnConfig(
            privateKey = privateKey,
            peerPublicKey = peerPublicKey,
            presharedKey = presharedKey,
            addressIpv4 = addressIpv4,
            addressPrefixLength = addressPrefixLength,
            dnsIpv4 = dnsIpv4,
            endpointHost = host,
            endpointPort = port,
            persistentKeepaliveSecs = persistentKeepalive,
        )
    }

    /** Prüft eine gepunktete IPv4-Notation streng selbst, statt `InetAddress.getByName(...)` zu
     * nutzen: letzteres akzeptiert auch Hostnamen (und löst sie im Zweifel per DNS auf — auf dem
     * Main-Thread der UI eine Netzwerkoperation) und erlaubt historische Kurzformen wie `10.1`.
     * Für eine Adresse, die anschließend 1:1 an `VpnService.Builder.addAddress(...)` geht, ist
     * genau die enge Auslegung die richtige. */
    internal fun isIpv4(raw: String): Boolean {
        val parts = raw.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } && (part.toIntOrNull() ?: -1) in 0..255
        }
    }

    /** `Address = 10.66.0.2/32` bzw. eine wg-quick-übliche kommaseparierte Liste mit zusätzlichen
     * IPv6-Einträgen. Liefert den ersten IPv4-Eintrag als `(adresse, präfixlänge)`; eine fehlende
     * Präfixangabe gilt wg-quick-konform als Host-Route (`/32`). `null`, wenn kein einziger
     * gültiger IPv4-Eintrag enthalten ist. */
    private fun parseAddress(raw: String): Pair<String, Int>? = firstIpv4Entry(raw)

    private fun firstIpv4Entry(raw: String): Pair<String, Int>? {
        for (entry in raw.split(',')) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            val slash = trimmed.indexOf('/')
            val address = if (slash < 0) trimmed else trimmed.substring(0, slash)
            if (!isIpv4(address)) continue // IPv6-Eintrag o. Ä. — überspringen, kein Fehler.
            val prefix = if (slash < 0) {
                IPV4_HOST_PREFIX_LENGTH
            } else {
                trimmed.substring(slash + 1).toIntOrNull()?.takeIf { it in 0..32 } ?: continue
            }
            return address to prefix
        }
        return null
    }

    private const val IPV4_HOST_PREFIX_LENGTH = 32

    private fun decodeKey(base64: String, field: String): Result<ByteArray> =
        try {
            Result.success(Base64.getDecoder().decode(base64.trim()))
        } catch (e: IllegalArgumentException) {
            Result.failure(ChildVpnConfigParseException(ChildVpnConfigParseError.InvalidBase64Key(field)))
        }

    /** `host:port`, wobei `host` selbst IPv6-Klammernotation (`[::1]:51820`) tragen darf — wg-quick
     * erlaubt beides. */
    private fun parseEndpoint(raw: String): Pair<String, Int>? {
        val trimmed = raw.trim()
        val lastColon = trimmed.lastIndexOf(':')
        if (lastColon <= 0 || lastColon == trimmed.length - 1) return null
        var host = trimmed.substring(0, lastColon)
        val port = trimmed.substring(lastColon + 1).toIntOrNull() ?: return null
        if (host.startsWith('[') && host.endsWith(']')) {
            host = host.substring(1, host.length - 1)
        }
        if (host.isBlank() || port !in 1..65535) return null
        return host to port
    }

    /** Liefert `(interfaceFields, peerFields)` — `peerFields` ist `null`, wenn kein `[Peer]`-
     * Abschnitt vorkommt (ChildVPN braucht genau einen Peer, kein Multi-Peer-Setup). `null` als
     * Ganzes, wenn nicht einmal ein `[Interface]`-Abschnitt existiert. */
    private fun splitSections(text: String): Pair<Map<String, String>, Map<String, String>?>? {
        var currentSection: String? = null
        var interfaceFields: MutableMap<String, String>? = null
        var peerFields: MutableMap<String, String>? = null

        for (rawLine in text.lineSequence()) {
            val line = rawLine.substringBefore('#').substringBefore(';').trim()
            if (line.isEmpty()) continue
            if (line.startsWith('[') && line.endsWith(']')) {
                currentSection = line.substring(1, line.length - 1).trim().lowercase()
                when (currentSection) {
                    "interface" -> if (interfaceFields == null) interfaceFields = mutableMapOf()
                    "peer" -> if (peerFields == null) peerFields = mutableMapOf()
                }
                continue
            }
            val separator = line.indexOf('=')
            if (separator < 0) continue
            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            when (currentSection) {
                "interface" -> interfaceFields?.set(key, value)
                "peer" -> peerFields?.set(key, value)
            }
        }

        val interfaceResult = interfaceFields ?: return null
        return interfaceResult to peerFields
    }
}

/** Trägt den strukturierten [ChildVpnConfigParseError] als echte Exception durch `runCatching`/
 * `Result`, damit [ChildVpnConfigParser.parse]s Aufrufer den genauen Fehlergrund unterscheiden
 * kann (z. B. für eine gezielte Fehlermeldung in der UI) statt nur eines generischen Fehlschlags. */
class ChildVpnConfigParseException(val error: ChildVpnConfigParseError) : Exception(error.toString())
