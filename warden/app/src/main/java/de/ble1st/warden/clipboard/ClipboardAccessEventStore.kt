package de.ble1st.warden.clipboard

import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.domain.clipboard.ClipboardAccessCodec
import de.ble1st.warden.domain.clipboard.ClipboardAccessEvent

/**
 * Persistiert die von [ClipboardAccessibilityService] erfassten Cross-App-Zugriffsereignisse —
 * dasselbe Ringpuffer-Muster wie `de.ble1st.warden.logging.SecurityEventStore` (dortiges
 * Klassendoc erklärt die Deckel-Begründung), nur mit einem deutlich kleineren [MAX_EVENTS]:
 * Sicherheits-/Netzwerklogs fallen tausendfach täglich an, ein paste-artiger Burst dagegen nur
 * gelegentlich (s. `ClipboardAccessDecision`s `MIN_BURST_CHARS`-Filter) — 300 Einträge decken
 * realistisch mehrere Wochen Nutzung ab, ohne dass jeder einzelne Schreibvorgang eine unnötig
 * große Datei neu verschlüsseln muss.
 *
 * Kein Hash-Chaining — dieselbe Begründung wie bei `SecurityEventStore`: das ist keine
 * Manipulationsschutz-Aufzeichnung von Wardens eigenen Entscheidungen, sondern reine
 * Beobachtungshistorie für die Anzeige im Dashboard. Die *Tatsache*, dass ein Zugriff erkannt
 * wurde, landet zusätzlich (aber ohne Text) im echten `HashChainLogStore`, s.
 * [ClipboardAccessController].
 */
class ClipboardAccessEventStore(private val envelopeFile: EnvelopeFile) {

    fun append(events: List<ClipboardAccessEvent>) {
        if (events.isEmpty()) return
        val combined = (read().events + events).takeLast(MAX_EVENTS)
        envelopeFile.write(ClipboardAccessCodec.encode(combined).toByteArray())
    }

    /** Leerer, aber gültiger Zustand, solange noch nie geschrieben wurde — ein Lesefehler einer
     * *vorhandenen* Datei wird dagegen durchgereicht, nicht als "keine Daten" verkauft (s.
     * `EnvelopeFile`-Klassendoc, gleiche Konvention wie überall sonst in diesem Projekt). */
    fun read(): ClipboardAccessCodec.DecodeResult {
        if (!envelopeFile.hasData()) return ClipboardAccessCodec.DecodeResult(emptyList(), 0)
        return ClipboardAccessCodec.decode(String(envelopeFile.read()))
    }

    fun clear() {
        envelopeFile.clearStorage()
    }

    companion object {
        const val MAX_EVENTS = 300
    }
}
