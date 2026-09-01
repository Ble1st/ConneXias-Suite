package de.ble1st.warden.crypto

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import uniffi.connexias_engine.Envelope
import uniffi.connexias_engine.KeyWrapper

/**
 * Meilenstein B.4 (Konzept 19): "Envelope-Schema end-to-end (verschlüsselt schreiben/lesen
 * einer Testdatei)". Verkabelt B.3 (Keystore-KEK-Wrap/Unwrap, [KeystoreKek]) mit B.1/B.2
 * (`seal`/`open`) zu einem benutzbaren At-Rest-Speicherformat für genau eine Datei: ein
 * Klartext-DEK wird einmalig erzeugt, mit dem Keystore-KEK gewrappt und neben der eigentlichen
 * Envelope-Datei abgelegt; jeder weitere Schreib-/Lesevorgang entwrappt denselben DEK erneut
 * (kein Re-Wrap pro Zugriff, kein zweiter DEK).
 *
 * Bewusst dünn und dateibasiert — die eigentliche Safeguard-Registry- und Log-Persistenz
 * (Meilenstein C/B.5/B.6) bekommt ihre eigene, robustere Speicherstrategie (u. a. Hash-Kette,
 * atomare Writes); dies hier ist der Nachweis, dass das Envelope-Schema (Konzept Abschnitt 10)
 * tatsächlich Ende-zu-Ende funktioniert, nicht die endgültige Storage-Schicht.
 *
 * `context` bindet den Zweck als AAD (Konzept 10a) — z. B. `"warden:registry:v1"` — und muss bei
 * jedem Lesevorgang identisch zum Schreibvorgang sein, sonst schlägt `open()` fehl.
 *
 * Fail-Safe (Invariante 6): jeder Fehlerfall (fehlende/kaputte DEK-Datei, fehlgeschlagenes
 * Unwrap, fehlgeschlagenes Open) wirft eine Exception statt still leeren/falschen Klartext zu
 * liefern — Aufrufer dürfen eine gefangene Exception nie als "keine Daten vorhanden" behandeln.
 *
 * **Seit Meilenstein B.6 atomarer Write:** `write()` schreibt zuerst in eine Temp-Datei im
 * selben Verzeichnis und ersetzt `dataFile` dann per `Files.move(..., ATOMIC_MOVE)` — auf
 * Android-internem Speicher (ein Dateisystem, kein Cross-Filesystem-Move) ein echter,
 * kernel-atomarer `rename()`. Ein Crash mitten im Schreiben hinterlässt entweder die alte,
 * vollständig gültige Datei oder die neue — nie eine angerissene Datei mit halb geschriebenem
 * Ciphertext. Vorher (B.4) wurde direkt in `dataFile` geschrieben; das genügte für den reinen
 * Ende-zu-Ende-Nachweis, war aber nicht crashsicher — siehe die B.4-Notiz oben, dass atomare
 * Writes explizit mit B.5/B.6 nachgezogen werden. Reine Diagnosepuffer wie `LocalRingTree`
 * (B.5) profitieren gleich mit, ohne selbst etwas dafür zu tun.
 *
 * **Seit der Stabilisierungsrunde nach G (Log-Rotation, `HashChainLogStore`):** [dataFile] ist
 * öffentlich lesbar, [sibling] baut eine Geschwister-Instanz im selben Verzeichnis unter einem
 * anderen Dateinamen. Ändert nichts am Verhalten von [write]/[read] für bestehende Aufrufer.
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

    /** Ob bereits ein DEK für diese Datei existiert (gewrappt persistiert) — reine Diagnose. */
    fun hasDek(): Boolean = wrappedDekFile.exists()

    /** Whether the ciphertext file exists — independent of [hasDek]. A missing DEK with leftover
     * data is a corruption signal, not "no data yet". */
    fun hasData(): Boolean = dataFile.exists()

    /** Whether ciphertext or wrapped DEK exists. Leftover of either is "was configured",
     * not "no data yet" — callers must [read] and fail, not return empty defaults. */
    fun hasStorage(): Boolean = hasDek() || hasData()

    /** Deletes both ciphertext and wrapped DEK. Used by failsafe PIN recovery so a corrupted
     * blob becomes [de.ble1st.warden.domain.pin.WardenPinStateDecision.LoadResult.NotYetConfigured]
     * instead of staying [de.ble1st.warden.domain.pin.WardenPinStateDecision.LoadResult.Corrupted]. */
    fun clearStorage() {
        dataFile.delete()
        wrappedDekFile.delete()
    }

    /** Geschwister-Envelope im selben Verzeichnis unter [dataFileName], mit demselben gewrappten
     * DEK und AAD-Kontext wie diese Instanz (kein Re-Wrap) — Grundlage für `HashChainLogStore`s
     * rotierte Archiv-Segmente (Stabilisierung nach G), die alle denselben Log-DEK teilen. */
    fun sibling(dataFileName: String): EnvelopeFile =
        EnvelopeFile(File(dataFile.parentFile, dataFileName), wrappedDekFile, wrapper, context)

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

/**
 * Schreibt `bytes` crashsicher nach `target`: erst vollständig in eine Temp-Datei im selben
 * Verzeichnis (damit `ATOMIC_MOVE` garantiert innerhalb desselben Dateisystems bleibt), dann
 * per atomarem Rename an die Zielstelle. Räumt die Temp-Datei bei jedem Fehlschlag auf, statt
 * verwaiste `.tmp-*`-Dateien zurückzulassen.
 */
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

/**
 * Minimales, selbstbeschreibendes Binärformat für einen [Envelope] auf der Platte:
 * `[1 Byte Nonce-Länge][Nonce][Ciphertext]`. Kein CBOR/Längen-Prefix für den Ciphertext nötig —
 * dessen Länge ergibt sich aus dem Rest der Datei. (CBOR-Deterministic-Encoding, Konzept 6a,
 * bleibt dem Sentinel-Blob vorbehalten, der ein eigenes, mehrfeldriges Schema braucht.)
 */
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
