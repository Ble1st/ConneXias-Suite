package de.ble1st.warden.failsafe

import de.ble1st.warden.crypto.Engine
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.domain.failsafe.FailsafeChallengeRecord
import de.ble1st.warden.domain.failsafe.FailsafeChallengeRecordCodec
import de.ble1st.warden.domain.failsafe.FailsafeChallengeTtl

/**
 * Meilenstein D.3 (Konzept Abschnitt 9): persistiert die aktuell ausstehende Failsafe-Challenge
 * über [EnvelopeFile] ([FailsafeStorage.buildChallengeFile]) — **nicht** nur im Prozessspeicher.
 *
 * **Warum persistiert statt In-Memory:** eine Ed25519-Signatur von Hand zu berechnen (Air-Gap-
 * Maschine holen, Challenge abtippen, `failsafe-keytool sign` ausführen, Response zurück
 * übertragen) kann Minuten dauern; überlebt Wardens Prozess diese Zeitspanne nicht (Low-Memory-
 * Kill, Neustart), würde eine rein In-Memory gehaltene Challenge verloren gehen und der
 * Besitzer stünde mit einer fertigen Response ohne passende, noch bekannte Challenge da.
 *
 * Leeres `ByteArray` als interner Sentinel für "keine Challenge ausstehend" (statt einer
 * separaten "Datei existiert/existiert nicht"-Fallunterscheidung wie bei [FailsafeKeyStore] —
 * hier gibt es zusätzlich den Zustand "ausstehende Challenge wurde konsumiert/verworfen", der
 * über bloßes Fehlen der Datei hinausgeht) — [pendingRecord] übersetzt das nach außen einheit-
 * lich auf `null`.
 *
 * Records carry an issue timestamp. After [FailsafeChallengeTtl] a stolen already-signed
 * response is useless; [pendingChallenge] then returns `null` (same as "none").
 */
class FailsafeChallengeStore(private val envelopeFile: EnvelopeFile) {

    /** Erzeugt eine neue, zufällige Challenge, persistiert und liefert sie zur Anzeige. Überschreibt
     * eine eventuell noch offene, unbeantwortete vorherige Challenge (Konzept: genau eine
     * ausstehende Challenge zur Zeit — ein neuer Durchlauf ersetzt einen alten). */
    fun issueNewChallenge(
        lengthBytes: UInt = CHALLENGE_LENGTH_BYTES,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ByteArray {
        val challenge = Engine.generateChallenge(lengthBytes)
        envelopeFile.write(
            FailsafeChallengeRecordCodec.encode(
                FailsafeChallengeRecord(challenge = challenge, issuedAtEpochMs = nowEpochMs),
            ),
        )
        return challenge
    }

    /** Aktuell ausstehende, noch nicht abgelaufene Challenge, oder `null`. */
    fun pendingChallenge(nowEpochMs: Long = System.currentTimeMillis()): ByteArray? {
        val record = pendingRecord() ?: return null
        if (FailsafeChallengeTtl.isExpired(record.issuedAtEpochMs, nowEpochMs)) return null
        return record.challenge
    }

    fun pendingRecord(): FailsafeChallengeRecord? {
        if (!envelopeFile.hasStorage()) return null
        val stored = envelopeFile.read()
        if (stored.isEmpty()) return null
        return FailsafeChallengeRecordCodec.decode(stored)
    }

    /** Verwirft die ausstehende Challenge (nach erfolgreichem oder endgültig abgebrochenem
     * Durchlauf) — verhindert, dass eine spätere, unabhängig erratene/mitgehörte Response gegen
     * eine alte Challenge nochmals versucht werden kann. */
    fun clearChallenge() {
        envelopeFile.write(ByteArray(0))
    }

    companion object {
        const val CHALLENGE_LENGTH_BYTES: UInt = 32u
    }
}
