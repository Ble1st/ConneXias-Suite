package de.ble1st.warden.pin

import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.domain.pin.WardenPinBlob
import de.ble1st.warden.domain.pin.WardenPinBlobCodec
import de.ble1st.warden.domain.pin.WardenPinReplayAnchorCodec
import de.ble1st.warden.domain.pin.WardenPinReplayDecision

/**
 * Meilenstein H.4/H.5/H.6 (Konzept Abschnitt 6/6a): verkabelt [WardenPinBlobCodec] (Kodierung)
 * mit [EnvelopeFile] (AEAD-Versiegelung über den TEE-gebundenen Keystore-KEK,
 * [WardenPinStorage]) zu einer benutzbaren Persistenz — dasselbe "Baustein zuerst, Verkabelung
 * folgt"-Muster wie überall im Projekt (z. B. `SafeguardRegistryStore`, `FailsafeKeyStore`).
 *
 * **[persistNewVersion] ist der einzige Schreibpfad** (Konzept 6: Zähler-/Ketten-Fortschritt darf
 * nie vergessen werden können) — lädt selbst den aktuellen Stand (oder [WardenPinBlob.genesis],
 * falls noch keine Datei existiert), übergibt ihn an `mutate` und überschreibt dessen `counter`/
 * `previousHash` anschließend **unbedingt** mit den korrekten, aus dem aktuellen Stand
 * abgeleiteten Werten — `mutate` kann also `counter`/`previousHash` gar nicht versehentlich falsch
 * setzen, selbst wenn es das versuchte.
 *
 * Anders als im ConneXias-Framework-Quellprojekt (dort zusätzlich `recoverFromTrustedBase`,
 * basierend auf Wardens gespiegeltem Cross-APK-Zähler/-Hash) gibt es hier **keinen** separaten
 * Recovery-Schreibpfad mehr — ein [de.ble1st.warden.domain.pin.WardenPinStateDecision.LoadResult
 * .Corrupted]-Zustand wird stattdessen über den ohnehin vorhandenen Offline-Failsafe behandelt
 * (s. Plan-Abschnitt "Presence: Sentinels PIN-Logik portiert"), keinen zweiten, jetzt
 * redundanten Mechanismus.
 *
 * [replayAnchorFile] is a second envelope (own KEK/AAD). [load] refuses a blob whose
 * counter/hash is behind that slot — a single-file restore of an older pin envelope
 * becomes [de.ble1st.warden.domain.pin.WardenPinStateDecision.LoadResult.Corrupted].
 */
class WardenPinStore(
    private val envelopeFile: EnvelopeFile,
    private val replayAnchorFile: EnvelopeFile,
) {

    fun exists(): Boolean = blobPresent() || anchorPresent()

    /** Failsafe recovery: drop both files so the next load is [de.ble1st.warden.domain.pin.WardenPinStateDecision.LoadResult.NotYetConfigured].
     * Cannot go through [persistNewVersion] — a corrupted blob makes [load] throw. */
    fun clearForRecovery() {
        envelopeFile.clearStorage()
        replayAnchorFile.clearStorage()
    }

    /** Wirft, wenn keine Datei existiert oder die Datei nicht entschlüsselt/dekodiert werden
     * kann — Aufrufer sollten stattdessen [de.ble1st.warden.domain.pin.WardenPinStateDecision
     * .load] verwenden, das beide Fälle explizit unterscheidet, statt diese Methode direkt mit
     * eigenem try/catch zu umgeben. */
    fun load(): WardenPinBlob {
        val blob = if (blobPresent()) {
            WardenPinBlobCodec.decode(envelopeFile.read())
        } else {
            null
        }
        val anchor = if (anchorPresent()) {
            WardenPinReplayAnchorCodec.decode(replayAnchorFile.read())
        } else {
            null
        }
        val decision = WardenPinReplayDecision.evaluate(
            blobPresent = blob != null,
            blobCounter = blob?.counter ?: 0L,
            blobHash = blob?.let { WardenPinBlobCodec.hashOf(it) } ?: ByteArray(32),
            anchorPresent = anchor != null,
            anchorCounter = anchor?.first ?: 0L,
            anchorHash = anchor?.second ?: ByteArray(32),
        )
        when (decision) {
            is WardenPinReplayDecision.Result.Reject ->
                throw IllegalStateException("PIN replay check failed: ${decision.reason}")
            WardenPinReplayDecision.Result.Accept ->
                return requireNotNull(blob)
            WardenPinReplayDecision.Result.AcceptAndWriteAnchor -> {
                val accepted = requireNotNull(blob)
                persistAnchor(accepted)
                return accepted
            }
        }
    }

    fun persistNewVersion(mutate: (WardenPinBlob) -> WardenPinBlob): WardenPinBlob {
        val current = if (exists()) load() else WardenPinBlob.genesis()
        val next = mutate(current).copy(
            counter = current.counter + 1,
            previousHash = WardenPinBlobCodec.hashOf(current),
        )
        envelopeFile.write(WardenPinBlobCodec.encode(next))
        persistAnchor(next)
        return next
    }

    private fun persistAnchor(blob: WardenPinBlob) {
        replayAnchorFile.write(
            WardenPinReplayAnchorCodec.encode(blob.counter, WardenPinBlobCodec.hashOf(blob)),
        )
    }

    private fun blobPresent(): Boolean = envelopeFile.hasStorage()

    private fun anchorPresent(): Boolean = replayAnchorFile.hasStorage()
}
