package de.ble1st.warden.sentinel.domain

/**
 * 1:1 Port der Interpretations-Logik aus `de.ble1st.warden.domain.pin.WardenPinStateDecision`
 * (s. dessen Klassendoc) — reine Fail-Safe-Interpretation eines evtl. vorhandenen, evtl. kaputten
 * Blobs, kennt weder Envelope noch Keystore.
 */
object SentinelPinStateDecision {

    sealed class LoadResult {
        data class Loaded(val blob: SentinelPinBlob) : LoadResult()
        data object NotYetConfigured : LoadResult()

        /** Muss vom Aufrufer als gesperrt behandelt werden — nie als [NotYetConfigured] maskiert
         * (Fail-Safe): ein Angreifer, der die Datei zerstört, darf dadurch nie erzwingen können,
         * dass Sentinel wieder im PIN-losen Ersteinrichtungs-Zustand erscheint. */
        data class Corrupted(val cause: Throwable) : LoadResult()
    }

    fun load(fileExists: Boolean, decode: () -> SentinelPinBlob): LoadResult {
        if (!fileExists) return LoadResult.NotYetConfigured
        return try {
            val blob = decode()
            if (blob.pinHash.isEmpty()) LoadResult.NotYetConfigured else LoadResult.Loaded(blob)
        } catch (e: Exception) {
            LoadResult.Corrupted(e)
        }
    }
}
