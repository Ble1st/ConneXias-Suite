package de.ble1st.warden.sentinel.pin

import de.ble1st.warden.sentinel.crypto.EnvelopeFile
import de.ble1st.warden.sentinel.domain.SentinelPinBlob
import de.ble1st.warden.sentinel.domain.SentinelPinBlobCodec

/**
 * Verkabelt [SentinelPinBlobCodec] mit [EnvelopeFile] — analog Wardens `WardenPinStore`, aber
 * ohne dessen Hash-Ketten-/Replay-Anchor-Mechanik (s. [SentinelPinBlobCodec]-Klassendoc, warum
 * das hier bewusst entfällt). [persistNewVersion] bleibt trotzdem der einzige Schreibpfad —
 * dieselbe "ein Pfad, kein vergessenes Feld möglich"-Idee, nur ohne Zähler-Fortschritt.
 */
class SentinelPinStore(private val envelopeFile: EnvelopeFile) {

    fun exists(): Boolean = envelopeFile.hasStorage()

    fun load(): SentinelPinBlob = SentinelPinBlobCodec.decode(envelopeFile.read())

    fun persistNewVersion(mutate: (SentinelPinBlob) -> SentinelPinBlob): SentinelPinBlob {
        val current = if (exists()) load() else SentinelPinBlob.genesis()
        val next = mutate(current)
        envelopeFile.write(SentinelPinBlobCodec.encode(next))
        return next
    }
}
