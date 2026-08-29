package de.ble1st.warden.logging

import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.domain.securitylog.SecurityLogCodec
import de.ble1st.warden.domain.securitylog.SecurityLogRecord

/**
 * Persistiert die ausgewerteten System-Ereignisse (2026-08-28) — die Senke, die bislang fehlte:
 * `WardenDeviceAdminReceiver` rief die DPM-Batches zwar ab, protokollierte aber nur deren Anzahl
 * und verwarf den Inhalt.
 *
 * **Ringpuffer mit fester Obergrenze** ([MAX_RECORDS]): Netzwerk-Logging allein produziert im
 * Normalbetrieb tausende Ereignisse pro Tag. Ohne Deckel wüchse die Datei unbegrenzt, und jeder
 * Schreibvorgang müsste sie vollständig neu verschlüsseln — die Grenze ist hier also nicht nur
 * Speicher-, sondern vor allem Laufzeitschutz. Verworfen wird immer am ältesten Ende.
 *
 * **Kein Hash-Chaining, anders als [HashChainLogStore].** Das Audit-Log dokumentiert Wardens
 * *eigene* Entscheidungen und muss deshalb manipulationssichtbar sein. Diese Datei ist dagegen eine
 * Kopie dessen, was `system_server` ohnehin selbst führt — eine Kette würde hier Aufwand pro
 * Ereignis kosten, ohne eine Aussage zu stützen, die nicht schon vom System kommt.
 */
class SecurityEventStore(private val envelopeFile: EnvelopeFile) {

    fun append(records: List<SecurityLogRecord>) {
        if (records.isEmpty()) return
        val combined = (read().records + records).takeLast(MAX_RECORDS)
        envelopeFile.write(SecurityLogCodec.encode(combined).toByteArray())
    }

    /** Leerer, aber gültiger Zustand, solange noch nie geschrieben wurde — ein Lesefehler einer
     * *vorhandenen* Datei wird dagegen wie überall in diesem Projekt durchgereicht, nicht als
     * "keine Daten" verkauft (s. `EnvelopeFile`-Klassendoc). */
    fun read(): SecurityLogCodec.DecodeResult {
        if (!envelopeFile.hasData()) return SecurityLogCodec.DecodeResult(emptyList(), 0)
        return SecurityLogCodec.decode(String(envelopeFile.read()))
    }

    fun clear() {
        envelopeFile.clearStorage()
    }

    companion object {
        const val MAX_RECORDS = 2_000
    }
}
