package de.ble1st.warden.domain.securitylog

/**
 * Zeilenformat für [de.ble1st.warden.logging.SecurityEventStore] — eine Zeile je Ereignis, Felder
 * getrennt durch den ASCII-Unit-Separator (Zeichen 31). Bewusst kein JSON: es gibt genau drei
 * Felder, das Format wird nur von dieser einen Klasse gelesen und geschrieben, und der Store
 * schreibt bei jedem eintreffenden Batch — ein Parser-Round-Trip pro Ereignis wäre reine Last
 * ohne Gegenwert. Das Trennzeichen ist als `31.toChar()` geschrieben statt als Escape-Literal,
 * damit in dieser Datei kein unsichtbares Steuerzeichen im Quelltext steht.
 *
 * **Fail-safe-Abweichung, bewusst begründet:** anders als bei PIN-Blob oder Registry (dort führt
 * ein Dekodierfehler zur Exception, s. `EnvelopeFile`-Doc) werden hier beschädigte *Einzelzeilen*
 * übersprungen statt die ganze Datei zu verwerfen — an diesem Inhalt hängt keine
 * Sicherheitsentscheidung, und ein einzelnes unlesbares Ereignis darf nicht die gesamte übrige
 * Historie unsichtbar machen. Die Anzahl übersprungener Zeilen bleibt am Aufrufer sichtbar
 * ([DecodeResult.skippedLines]), damit "leer" und "beschädigt" nicht dasselbe aussehen.
 */
object SecurityLogCodec {

    /** ASCII Unit Separator — kommt in Paketnamen, Kommandozeilen und Hostnamen nicht vor. */
    val FIELD_SEPARATOR: Char = 31.toChar()
    private const val LINE_SEPARATOR = '\n'

    fun encode(records: List<SecurityLogRecord>): String =
        records.joinToString(LINE_SEPARATOR.toString()) { record ->
            listOf(
                record.timestampMillis.toString(),
                record.type.name,
                sanitize(record.detail),
            ).joinToString(FIELD_SEPARATOR.toString())
        }

    fun decode(raw: String): DecodeResult {
        if (raw.isBlank()) return DecodeResult(emptyList(), 0)
        val records = mutableListOf<SecurityLogRecord>()
        var skipped = 0
        for (line in raw.split(LINE_SEPARATOR)) {
            if (line.isBlank()) continue
            val parts = line.split(FIELD_SEPARATOR)
            val timestamp = parts.getOrNull(0)?.toLongOrNull()
            val type = parts.getOrNull(1)?.let { name -> SecurityLogEventType.entries.firstOrNull { it.name == name } }
            if (parts.size != 3 || timestamp == null || type == null) {
                skipped++
                continue
            }
            records += SecurityLogRecord(timestamp, type, parts[2])
        }
        return DecodeResult(records, skipped)
    }

    /** Trennzeichen im Nutzlast-Text würden die Zeile zerlegen — ersetzt statt escaped, weil der
     * Text reiner Anzeigeinhalt ist und niemand ihn zurückwandeln muss. */
    private fun sanitize(detail: String): String =
        detail.replace(FIELD_SEPARATOR, ' ').replace(LINE_SEPARATOR, ' ')

    data class DecodeResult(val records: List<SecurityLogRecord>, val skippedLines: Int)
}
