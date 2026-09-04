package de.ble1st.warden.domain.clipboard

/**
 * Zeilenformat für [de.ble1st.warden.clipboard.ClipboardAccessEventStore] — dasselbe Muster wie
 * `de.ble1st.warden.domain.securitylog.SecurityLogCodec` (dortiges Klassendoc erklärt die
 * Trennzeichen-Wahl/Nicht-JSON-Begründung im Detail, hier identisch übernommen): eine Zeile je
 * Ereignis, Felder getrennt durch den ASCII-Unit-Separator (Zeichen 31).
 *
 * **Anders als dort werden hier Zeilen-Trennzeichen im erfassten [ClipboardAccessEvent.text] nicht
 * durch Leerzeichen ersetzt, sondern mit einem einfachen Escape kodiert** (`\n` → `n`,
 * Trennzeichen selbst → `u`, `` → ``): anders als ein Log-Detailtext ist
 * dieser Inhalt eingefügter Nutzertext — Zeilenumbrüche darin sind Teil der eigentlichen
 * Information (z. B. eine mehrzeilig eingefügte Adresse), kein Anzeigeartefakt, das man verlustfrei
 * plätten dürfte.
 */
object ClipboardAccessCodec {

    val FIELD_SEPARATOR: Char = 31.toChar()
    private const val LINE_SEPARATOR = '\n'
    private const val ESCAPE = ''

    fun encode(events: List<ClipboardAccessEvent>): String =
        events.joinToString(LINE_SEPARATOR.toString()) { event ->
            listOf(
                event.timestampMillis.toString(),
                escape(event.packageName),
                escape(event.appLabel),
                escape(event.text),
            ).joinToString(FIELD_SEPARATOR.toString())
        }

    fun decode(raw: String): DecodeResult {
        if (raw.isBlank()) return DecodeResult(emptyList(), 0)
        val events = mutableListOf<ClipboardAccessEvent>()
        var skipped = 0
        for (line in raw.split(LINE_SEPARATOR)) {
            if (line.isBlank()) continue
            val parts = line.split(FIELD_SEPARATOR)
            val timestamp = parts.getOrNull(0)?.toLongOrNull()
            if (parts.size != 4 || timestamp == null) {
                skipped++
                continue
            }
            events += ClipboardAccessEvent(
                timestampMillis = timestamp,
                packageName = unescape(parts[1]),
                appLabel = unescape(parts[2]),
                text = unescape(parts[3]),
            )
        }
        return DecodeResult(events, skipped)
    }

    private fun escape(value: String): String =
        value
            .replace(ESCAPE.toString(), "$ESCAPE$ESCAPE")
            .replace(FIELD_SEPARATOR.toString(), "${ESCAPE}u")
            .replace(LINE_SEPARATOR.toString(), "${ESCAPE}n")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == ESCAPE && i + 1 < value.length) {
                when (value[i + 1]) {
                    'n' -> out.append(LINE_SEPARATOR)
                    'u' -> out.append(FIELD_SEPARATOR)
                    ESCAPE -> out.append(ESCAPE)
                    else -> out.append(c)
                }
                i += 2
            } else {
                out.append(c)
                i += 1
            }
        }
        return out.toString()
    }

    data class DecodeResult(val events: List<ClipboardAccessEvent>, val skippedLines: Int)
}
