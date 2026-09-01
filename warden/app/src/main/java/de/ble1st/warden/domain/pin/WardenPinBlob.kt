package de.ble1st.warden.domain.pin

/**
 * Meilenstein H.4/H.5/H.6/H.7 (Konzept Abschnitt 6/6a/19): Wardens PIN-Presence vollständiger,
 * versionierter Zustand (portiert aus Sentinels ursprünglichem `SentinelBlob`) — der "signierte
 * Zustands-Blob" aus H.4. Framework-frei (reine Datenklasse); die tatsächliche
 * AEAD-Versiegelung/TEE-Bindung übernimmt [de.ble1st.warden.pin.WardenPinStore] über dieselbe
 * `EnvelopeFile`/`KeystoreKek`-Kette wie überall im Projekt — "signiert" meint hier wie bei
 * Registry/Log (B.4/C.3) den AEAD-Auth-Tag über einem TEE-gebundenen Schlüssel, keine zusätzliche
 * Ed25519-Signatur (Konzept 6: "AES-256-GCM ... KEK aus TEE").
 *
 * Jede Zustandsänderung (PIN gesetzt/geändert, ver-/entsperrt, fehlgeschlagener Versuch) erzeugt
 * eine neue Version: [counter] erhöht sich um eins, [previousHash] verweist auf den
 * [WardenPinBlobCodec.hashOf] der vorherigen Version (H.6 Schicht "Hash-Kette", 6a Punkt 5) — ein
 * einzelner Schreibpfad ([de.ble1st.warden.pin.WardenPinStore.persistNewVersion]) statt mehrerer
 * unabhängiger Persistenzstellen, damit kein Aufrufer versehentlich eine Version ohne Zähler-/Ketten-Fortschritt
 * schreiben kann. [genesis] ist der reine In-Memory-Startwert für "noch keine Datei vorhanden" —
 * wird selbst nie persistiert, erst die erste echte Zustandsänderung erzeugt Version 1.
 */
data class WardenPinBlob(
    val counter: Long,
    val previousHash: ByteArray,
    val locked: Boolean,
    val failedAttempts: Int,
    val backoffUntilEpochSeconds: Long,
    val pinHash: String,
) {
    init {
        require(previousHash.size == HASH_LENGTH_BYTES) {
            "previousHash muss $HASH_LENGTH_BYTES Byte lang sein, war ${previousHash.size}"
        }
        require(counter >= 0) { "counter darf nicht negativ sein" }
        require(failedAttempts >= 0) { "failedAttempts darf nicht negativ sein" }
    }

    // Strukturelle statt Referenzgleichheit für previousHash (ByteArray hat keine sinnvolle
    // equals()/hashCode()) — sonst gälten zwei inhaltsgleiche, aber unabhängig dekodierte Blobs
    // fälschlich als ungleich (relevant für Tests).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WardenPinBlob) return false
        return counter == other.counter &&
            previousHash.contentEquals(other.previousHash) &&
            locked == other.locked &&
            failedAttempts == other.failedAttempts &&
            backoffUntilEpochSeconds == other.backoffUntilEpochSeconds &&
            pinHash == other.pinHash
    }

    override fun hashCode(): Int {
        var result = counter.hashCode()
        result = 31 * result + previousHash.contentHashCode()
        result = 31 * result + locked.hashCode()
        result = 31 * result + failedAttempts
        result = 31 * result + backoffUntilEpochSeconds.hashCode()
        result = 31 * result + pinHash.hashCode()
        return result
    }

    companion object {
        const val HASH_LENGTH_BYTES = 32

        /** Noch keine PIN konfiguriert, "davor"-Hash sind [HASH_LENGTH_BYTES] Null-Bytes (kein
         * Vorgänger existiert). */
        fun genesis(): WardenPinBlob = WardenPinBlob(
            counter = 0,
            previousHash = ByteArray(HASH_LENGTH_BYTES),
            locked = false,
            failedAttempts = 0,
            backoffUntilEpochSeconds = 0,
            pinHash = "",
        )
    }
}
