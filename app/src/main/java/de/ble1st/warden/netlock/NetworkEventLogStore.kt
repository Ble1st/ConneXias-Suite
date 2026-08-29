package de.ble1st.warden.netlock

import android.content.Context
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * "Netz-Sperre" (2026-08-27): strukturierte Rohdaten aus `DevicePolicyManager.retrieveNetworkLogs`
 * — bewusst **getrennt** vom Audit-Hash-Chain-Log ([de.ble1st.warden.logging.HashChainLogStore]):
 * der Audit-Trail bekommt weiterhin nur eine Zähler-Zeile pro Batch (unverändert, s.
 * `WardenDeviceAdminReceiver.onNetworkLogsAvailable`), diese Store hier die vollen strukturierten
 * Einzel-Ereignisse (`DnsEvent`/`ConnectEvent`) für eine spätere Ansicht
 * ([NetworkLogViewerActivity]). Vermischung würde den Hash-Chain-Log aufblähen und dessen "jede
 * Korrektur/jedes sicherheitsrelevante Ereignis einzeln nachvollziehbar" -Zweck verwässern (s.
 * `EnvelopeFile`/`HashChainLogStore`-Klassendocs).
 *
 * **Ringpuffer, kein Hash-Chain:** anders als der Audit-Log ist das hier reine Diagnose/Forensik,
 * keine manipulationssichere Historie — ein einfacher, auf [MAX_ENTRIES] gekappter Puffer genügt
 * (dieselbe Kappungs-Überlegung wie `HashChainLogStore`s Rotation, nur ohne deren Ketten-Aufwand).
 * Device-Protected Storage wie [de.ble1st.warden.logging.LogStorage] — `onNetworkLogsAvailable`
 * kann grundsätzlich in jedem App-Zustand feuern, dieselbe Vorsicht wie beim zentralen Log-Store.
 */
class NetworkEventLogStore(private val envelopeFile: EnvelopeFile) {

    fun entries(): List<NetworkLogEntry> =
        if (envelopeFile.hasStorage()) decodeEntries(envelopeFile.read()) else emptyList()

    /** Hängt [newEntries] an und kappt auf [MAX_ENTRIES] (älteste zuerst verworfen). */
    fun append(newEntries: List<NetworkLogEntry>) {
        if (newEntries.isEmpty()) return
        val merged = (entries() + newEntries).takeLast(MAX_ENTRIES)
        envelopeFile.write(encodeEntries(merged))
    }

    companion object {
        const val MAX_ENTRIES = 2000

        private const val KEYSTORE_PURPOSE = "net_event_log"
        private const val ENVELOPE_CONTEXT = "warden:net_event_log:v1"
        private const val DATA_FILE_NAME = "net_event_log.envelope"
        private const val DEK_FILE_NAME = "net_event_log.dek"

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

enum class NetworkLogEventKind { DNS, CONNECT }

/** Ein einzelnes geparstes `NetworkEvent` — [detail] ist bei [NetworkLogEventKind.DNS] der
 * angefragte Hostname, bei [NetworkLogEventKind.CONNECT] `"ip:port"`. Beide DPM-Event-Typen
 * (`DnsEvent`/`ConnectEvent`) tragen unterschiedliche Nutzdaten-Felder — ein gemeinsames,
 * bereits-zu-String-normalisiertes [detail]-Feld hält diese Datenklasse (und ihr Storage-Format)
 * einfach, statt für jeden Typ ein eigenes Feld-Set zu pflegen. */
data class NetworkLogEntry(
    val timestampMillis: Long,
    val packageName: String?,
    val kind: NetworkLogEventKind,
    val detail: String,
)

/** Storage-Format, analog zu `SafeguardRegistryStore`s längenpräfigiertem Schema: `[4 Byte
 * Anzahl][je Eintrag: Long-Timestamp, Boolean-hasPackageName(+UTF), 1 Byte Kind-Ordinal, UTF-detail]`. */
internal fun encodeEntries(entries: List<NetworkLogEntry>): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use { data ->
        data.writeInt(entries.size)
        for (entry in entries) {
            data.writeLong(entry.timestampMillis)
            data.writeBoolean(entry.packageName != null)
            entry.packageName?.let { data.writeUTF(it) }
            data.writeByte(entry.kind.ordinal)
            data.writeUTF(entry.detail)
        }
    }
    return out.toByteArray()
}

internal fun decodeEntries(bytes: ByteArray): List<NetworkLogEntry> {
    val data = DataInputStream(bytes.inputStream())
    val count = data.readInt()
    val kinds = NetworkLogEventKind.entries
    return buildList {
        repeat(count) {
            val timestamp = data.readLong()
            val hasPackageName = data.readBoolean()
            val packageName = if (hasPackageName) data.readUTF() else null
            val kindOrdinal = data.readByte().toInt()
            val detail = data.readUTF()
            add(NetworkLogEntry(timestamp, packageName, kinds.getOrElse(kindOrdinal) { NetworkLogEventKind.CONNECT }, detail))
        }
    }
}
