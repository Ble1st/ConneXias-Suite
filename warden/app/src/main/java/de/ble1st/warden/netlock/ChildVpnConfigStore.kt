package de.ble1st.warden.netlock

import android.content.Context
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import de.ble1st.warden.domain.netlock.ChildVpnConfig
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * ChildVPN (2026-08-31, `docs/design-barbican-prozess-childvpn.md`): verschlüsselte Ablage der
 * geparsten [ChildVpnConfig]. Anders als [DomainBlocklistStore] (Domain-Namen tragen kein
 * Geheimnis) MUSS dies über [EnvelopeFile] laufen — `privateKey` ist echtes
 * WireGuard-Schlüsselmaterial, dessen Kompromittierung einem Angreifer den vollen ChildVPN-Zugang
 * zur VPS verschafft, genau die Art Geheimnis, für die dieser Speicherpfad existiert.
 *
 * Speichert die bereits geparsten/validierten Felder binär, nicht den rohen wg-quick-Text — spart
 * einen erneuten Parse-Durchlauf (samt erneuter Fehlerbehandlung) bei jedem [load]. Wie bei
 * [DomainBlocklistStore] wirft ein tatsächlich korrupter Datenbestand beim Lesen eine Exception
 * (aus [EnvelopeFile.read]/[decodeConfig]) statt still `null` zu liefern — `null` aus [load]
 * bedeutet ausschließlich "noch nie konfiguriert" ([EnvelopeFile.hasStorage] ist `false`), dieselbe
 * Fail-safe-Unterscheidung wie überall im Projekt.
 */
class ChildVpnConfigStore(private val envelopeFile: EnvelopeFile) {

    fun load(): ChildVpnConfig? =
        if (envelopeFile.hasStorage()) decodeConfig(envelopeFile.read()) else null

    fun save(config: ChildVpnConfig) {
        envelopeFile.write(encodeConfig(config))
    }

    /** Löscht die Konfiguration vollständig (Ciphertext + gewrappter DEK) — der Aufrufer muss
     * zusätzlich [de.ble1st.warden.netlock.BarbicanEngine.clearChildVpnConfig] rufen, damit ein
     * gerade laufender Tunnel das auch sofort übernimmt (dieser Store ist reine Persistenz, kein
     * Live-Zustand). */
    fun clear() {
        envelopeFile.clearStorage()
    }

    companion object {
        private const val KEYSTORE_PURPOSE = "child_vpn_config"
        private const val ENVELOPE_CONTEXT = "warden:child_vpn_config:v1"
        private const val DATA_FILE_NAME = "child_vpn_config.envelope"
        private const val DEK_FILE_NAME = "child_vpn_config.dek"

        fun buildEnvelopeFile(context: Context): EnvelopeFile {
            val deviceProtectedContext = context.createDeviceProtectedStorageContext()
            val kek = KeystoreKek.forPurpose(deviceProtectedContext, KEYSTORE_PURPOSE)
            return EnvelopeFile(
                dataFile = File(deviceProtectedContext.filesDir, DATA_FILE_NAME),
                wrappedDekFile = File(deviceProtectedContext.filesDir, DEK_FILE_NAME),
                wrapper = kek,
                context = ENVELOPE_CONTEXT.toByteArray(),
            )
        }
    }
}

private fun DataOutputStream.writeKey(key: ByteArray) {
    writeInt(key.size)
    write(key)
}

private fun DataInputStream.readKey(): ByteArray {
    val length = readInt()
    require(length in 1..MAX_KEY_BYTES) { "Schlüssellänge $length außerhalb des erlaubten Bereichs" }
    val bytes = ByteArray(length)
    readFully(bytes)
    return bytes
}

/**
 * Formatversion des von [encodeConfig] geschriebenen Binärsatzes. Eingeführt am 2026-09-01 zusammen
 * mit den Feldern `addressIpv4`/`addressPrefixLength`/`dnsIpv4` (s. [ChildVpnConfig]-Klassendoc):
 * ein vorher (v1, ohne Versionspräfix) geschriebener Satz beginnt mit der Schlüssellänge `32`, ein
 * neuer mit dieser Versionsnummer — die beiden sind dadurch eindeutig unterscheidbar, und ein alter
 * Satz wird nicht als neuer fehlinterpretiert. Ein alter Satz enthält die jetzt zwingend benötigte
 * `Address` schlicht nicht, kann also auch nicht migriert werden: [decodeConfig] wirft dafür eine
 * Exception mit klarer Handlungsanweisung, statt still `null` ("nie konfiguriert") zu liefern —
 * dieselbe Fail-safe-Unterscheidung wie im Klassendoc von [ChildVpnConfigStore] beschrieben.
 */
private const val FORMAT_VERSION = 2
private const val MAX_KEY_BYTES = 64

internal fun encodeConfig(config: ChildVpnConfig): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use { data ->
        data.writeInt(FORMAT_VERSION)
        data.writeKey(config.privateKey)
        data.writeKey(config.peerPublicKey)
        data.writeBoolean(config.presharedKey != null)
        config.presharedKey?.let { data.writeKey(it) }
        data.writeUTF(config.addressIpv4)
        data.writeInt(config.addressPrefixLength)
        data.writeBoolean(config.dnsIpv4 != null)
        config.dnsIpv4?.let { data.writeUTF(it) }
        data.writeUTF(config.endpointHost)
        data.writeInt(config.endpointPort)
        data.writeBoolean(config.persistentKeepaliveSecs != null)
        config.persistentKeepaliveSecs?.let { data.writeInt(it) }
    }
    return out.toByteArray()
}

internal fun decodeConfig(bytes: ByteArray): ChildVpnConfig {
    val data = DataInputStream(bytes.inputStream())
    val version = data.readInt()
    if (version != FORMAT_VERSION) {
        throw IllegalStateException(
            "ChildVPN-Konfiguration liegt im veralteten Format v$version vor (ohne die seit " +
                "2026-09-01 zwingende [Interface]-Address) — bitte die WireGuard-Konfiguration " +
                "erneut einlesen.",
        )
    }
    val privateKey = data.readKey()
    val peerPublicKey = data.readKey()
    val presharedKey = if (data.readBoolean()) data.readKey() else null
    val addressIpv4 = data.readUTF()
    val addressPrefixLength = data.readInt()
    val dnsIpv4 = if (data.readBoolean()) data.readUTF() else null
    val endpointHost = data.readUTF()
    val endpointPort = data.readInt()
    val persistentKeepaliveSecs = if (data.readBoolean()) data.readInt() else null
    return ChildVpnConfig(
        privateKey = privateKey,
        peerPublicKey = peerPublicKey,
        presharedKey = presharedKey,
        addressIpv4 = addressIpv4,
        addressPrefixLength = addressPrefixLength,
        dnsIpv4 = dnsIpv4,
        endpointHost = endpointHost,
        endpointPort = endpointPort,
        persistentKeepaliveSecs = persistentKeepaliveSecs,
    )
}
