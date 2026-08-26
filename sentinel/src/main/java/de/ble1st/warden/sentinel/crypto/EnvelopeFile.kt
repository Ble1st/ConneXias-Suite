package de.ble1st.warden.sentinel.crypto

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import uniffi.connexias_engine.Envelope
import uniffi.connexias_engine.KeyWrapper

/**
 * 1:1 Port von `de.ble1st.warden.crypto.EnvelopeFile` (s. dessen Klassendoc für die volle
 * Begründung des Envelope-Schemas, des atomaren Writes und der Fail-Safe-Haltung bei jedem
 * Lesefehler) — eigene, unabhängige Kopie in Sentinels eigenem Paket (Plan-Entscheidung
 * "Crypto-Sharing: Duplizieren"), verkabelt hier gegen Sentinels eigenes [Engine].
 */
class EnvelopeFile(
    val dataFile: File,
    private val wrappedDekFile: File,
    private val wrapper: KeyWrapper,
    private val context: ByteArray,
) {

    fun write(plaintext: ByteArray) {
        val dek = loadOrCreateDek()
        val envelope = Engine.seal(dek, context, plaintext)
        writeAtomically(dataFile, encodeEnvelope(envelope))
    }

    fun read(): ByteArray {
        check(dataFile.exists()) { "Envelope-Datei existiert nicht: ${dataFile.path}" }
        val dek = unwrapExistingDek()
        val envelope = decodeEnvelope(dataFile.readBytes())
        return Engine.open(dek, context, envelope)
    }

    fun hasDek(): Boolean = wrappedDekFile.exists()

    fun hasData(): Boolean = dataFile.exists()

    fun hasStorage(): Boolean = hasDek() || hasData()

    fun clearStorage() {
        dataFile.delete()
        wrappedDekFile.delete()
    }

    private fun unwrapExistingDek(): ByteArray {
        check(wrappedDekFile.exists()) { "DEK-Datei existiert nicht: ${wrappedDekFile.path}" }
        val wrapped = decodeEnvelope(wrappedDekFile.readBytes())
        return Engine.unwrapDek(wrapper, wrapped)
    }

    private fun loadOrCreateDek(): ByteArray {
        if (wrappedDekFile.exists()) {
            return unwrapExistingDek()
        }
        val generated = Engine.generateAndWrapDek(wrapper, DEK_LENGTH_BYTES)
        writeAtomically(wrappedDekFile, encodeEnvelope(generated.wrapped))
        return generated.dek
    }

    companion object {
        private const val DEK_LENGTH_BYTES: UInt = 32u
    }
}

internal fun writeAtomically(target: File, bytes: ByteArray) {
    val tempFile = File(target.parentFile, "${target.name}.tmp-${UUID.randomUUID()}")
    try {
        tempFile.writeBytes(bytes)
        Files.move(
            tempFile.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (e: Exception) {
        tempFile.delete()
        throw e
    }
}

internal fun encodeEnvelope(envelope: Envelope): ByteArray {
    val nonceLength = envelope.nonce.size
    require(nonceLength in 0..255) {
        "Nonce zu lang für 1-Byte-Längenpräfix ($nonceLength Byte)"
    }
    return byteArrayOf(nonceLength.toByte()) + envelope.nonce + envelope.ciphertext
}

internal fun decodeEnvelope(bytes: ByteArray): Envelope {
    require(bytes.isNotEmpty()) { "leere/fehlende Envelope-Datei" }
    val nonceLength = bytes[0].toInt() and 0xFF
    require(bytes.size >= 1 + nonceLength) {
        "Envelope-Datei zu kurz für angegebene Nonce-Länge ($nonceLength Byte)"
    }
    return Envelope(
        nonce = bytes.copyOfRange(1, 1 + nonceLength),
        ciphertext = bytes.copyOfRange(1 + nonceLength, bytes.size),
    )
}
