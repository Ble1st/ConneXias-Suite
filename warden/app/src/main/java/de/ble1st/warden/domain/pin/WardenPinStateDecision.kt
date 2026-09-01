package de.ble1st.warden.domain.pin

/**
 * Meilenstein H.4/H.5 (Konzept Abschnitt 6: "Fail-Safe: fehlendes/fehlerhaftes Signal ≠ 'normal'
 * → im letzten bestätigten Zustand bleiben bzw. sperren"). Reine Interpretation eines evtl.
 * vorhandenen, evtl. kaputten Blobs — kennt weder Envelope/Keystore noch Device-Protected-Storage,
 * `decode` kapselt die tatsächliche I/O als injizierte Lambda (dasselbe Testbarkeits-Muster wie
 * [de.ble1st.warden.domain.failsafe.FailsafeDecision]).
 *
 * Anders als im ConneXias-Framework-Quellprojekt gibt es hier kein `SentinelRollbackDecision`
 * mehr (Cross-APK-Zähler-/Hash-Spiegel-Vergleich gegen Warden) — Sentinel läuft jetzt selbst
 * in Wardens Prozess, es gibt keine zweite, unabhängige Partei mehr, gegen die gespiegelt werden
 * könnte (s. Plan-Abschnitt "Presence: Sentinels PIN-Logik portiert"). Ein [LoadResult.Corrupted]
 * wird stattdessen über den ohnehin vorhandenen Offline-Failsafe behandelt.
 */
object WardenPinStateDecision {

    sealed class LoadResult {
        /** Blob vorhanden und erfolgreich dekodiert. */
        data class Loaded(val blob: WardenPinBlob) : LoadResult()

        /** Nachweislich noch keine Datei vorhanden — Ersteinrichtungs-Zustand, kein Fehler. */
        data object NotYetConfigured : LoadResult()

        /** Datei vorhanden, aber Dekodierung/Entschlüsselung schlug fehl (Tamper, kaputte
         * Keystore-Bindung, kürzere/längere Datei als erwartet, ...). **Muss** vom Aufrufer als
         * gesperrt behandelt werden — nie als [NotYetConfigured] maskiert (Fail-Safe,
         * Invariante 6): ein Angreifer, der die Datei zerstört, darf dadurch nie den
         * Ersteinrichtungs-Zustand (kein PIN nötig) erzwingen können. */
        data class Corrupted(val cause: Throwable) : LoadResult()
    }

    /**
     * `fileExists` wird explizit übergeben statt intern per `File.exists()` geprüft — [decode]
     * wird **nur** aufgerufen, wenn `fileExists` wahr ist, damit ein Aufrufer nicht versehentlich
     * eine nicht existente Datei "dekodiert" und deren I/O-Exception fälschlich als
     * [Corrupted] statt als [NotYetConfigured] interpretiert.
     */
    fun load(fileExists: Boolean, decode: () -> WardenPinBlob): LoadResult {
        if (!fileExists) return LoadResult.NotYetConfigured
        return try {
            val blob = decode()
            // Empty hash after failsafe recovery (or a persisted genesis) is setup, not unlock.
            if (blob.pinHash.isEmpty()) LoadResult.NotYetConfigured else LoadResult.Loaded(blob)
        } catch (e: Exception) {
            LoadResult.Corrupted(e)
        }
    }
}
