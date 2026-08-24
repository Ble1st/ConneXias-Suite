package de.ble1st.warden.registry

import de.ble1st.warden.crypto.EnvelopeFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Meilenstein C.3 (Konzept 19): "Registry-Persistenz (Soll-Zustand, über Envelope B.4)".
 * Verschlüsselte Persistenz für [de.ble1st.warden.domain.registry.SafeguardRegistry]s
 * Soll-Zustand (`id -> Boolean`) über [EnvelopeFile] (B.4, seit B.6 mit atomarem Write). Kennt
 * `SafeguardRegistry` selbst nicht — arbeitet bewusst nur mit der reinen `Map<String, Boolean>`
 * (s. `SafeguardRegistry.desiredStateSnapshot`/`restoreDesiredState`), leichter testbar und ohne
 * Kopplung an die Registry-Klasse selbst. [PersistentSafeguardRegistry] verdrahtet beides.
 */
class SafeguardRegistryStore(private val envelopeFile: EnvelopeFile) {

    /** Zuletzt gespeicherter Soll-Zustand, oder eine leere Map, wenn noch nie gespeichert wurde.
     * Fail-Safe (Invariante 6): existiert bereits ein gewrappter DEK **oder** eine Datendatei,
     * aber das Lesen scheitert, wirft [EnvelopeFile.read] statt still eine leere Map zu liefern — ein
     * verschwundener Soll-Zustand darf nie als "noch nie gesetzt" durchgehen. */
    fun load(): Map<String, Boolean> =
        if (envelopeFile.hasStorage()) decodeDesiredState(envelopeFile.read()) else emptyMap()

    fun save(desiredState: Map<String, Boolean>) {
        // Overlay, never replace: a caller that only registered a subset (or omits lockdown
        // on purpose) must not wipe other IDs from the shared envelope.
        val merged = load() + desiredState
        envelopeFile.write(encodeDesiredState(merged))
    }
}

/**
 * Storage-Format, analog zu B.5/B.6s längenpräfigierten Formaten (kein CBOR für ein so
 * einfaches Schema nötig): `[4 Byte Anzahl][je Eintrag: UTF-id, 1 Byte Boolean]`.
 */
internal fun encodeDesiredState(state: Map<String, Boolean>): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use { data ->
        data.writeInt(state.size)
        for ((id, desired) in state) {
            data.writeUTF(id)
            data.writeBoolean(desired)
        }
    }
    return out.toByteArray()
}

internal fun decodeDesiredState(bytes: ByteArray): Map<String, Boolean> {
    val data = DataInputStream(bytes.inputStream())
    val count = data.readInt()
    return buildMap {
        repeat(count) {
            val id = data.readUTF()
            val desired = data.readBoolean()
            put(id, desired)
        }
    }
}
