package de.ble1st.warden.logging

import de.ble1st.warden.crypto.EnvelopeFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import timber.log.Timber

/**
 * Meilenstein B.5 (Konzept 19): "Timber-Fassade + `LocalRingTree` (verschlüsselter Ringpuffer
 * über B.4)". Ein [Timber.Tree], das die letzten [capacity] Log-Zeilen im Speicher hält und nach
 * jedem Eintrag als Ganzes verschlüsselt über [EnvelopeFile] (Meilenstein B.4) persistiert — ein
 * lokaler, verschlüsselter Diagnosepuffer für die aktuellste Historie, kein Sicherheitsprotokoll.
 *
 * Bewusst **kein** Fail-Safe-Anspruch (Invariante 6 gilt hier nicht): anders als der
 * hash-verkettete [HashChainLogStore] (B.6) ist der Ringpuffer ein reiner
 * Best-Effort-Diagnosespeicher ohne Sicherheitsfunktion — eine fehlende oder unlesbare
 * (z. B. manipulierte) Datei führt beim Start zu einem leeren Puffer statt zu einer Exception,
 * weil Datenverlust hier keine Sicherheitslücke öffnet, sondern höchstens Debug-Historie kostet.
 *
 * Puffer und Persistenz sind bewusst synchron und pro Log-Aufruf — für ein lokales
 * Debug-Werkzeug in dieser Größenordnung ist das einfacher/robuster als asynchrones Batching;
 * sollte Log-Volumen das später zum Performance-Problem machen, ist Batching ein rein interner
 * Optimierungsschritt ohne API-Änderung.
 */
class LocalRingTree(
    private val envelopeFile: EnvelopeFile,
    private val capacity: Int = DEFAULT_CAPACITY,
) : Timber.Tree() {

    private val buffer = ArrayDeque<LogLine>()

    init {
        require(capacity > 0) { "capacity muss positiv sein, war $capacity" }
        val restored = runCatching { decodeLines(envelopeFile.read()) }.getOrDefault(emptyList())
        buffer.addAll(restored.takeLast(capacity))
    }

    @Synchronized
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val fullMessage = if (t != null) "$message\n${t.stackTraceToString()}" else message
        buffer.addLast(LogLine(System.currentTimeMillis(), priority, tag, fullMessage))
        while (buffer.size > capacity) {
            buffer.removeFirst()
        }
        envelopeFile.write(encodeLines(buffer))
    }

    /** Momentaufnahme des aktuellen Puffers, älteste zuerst — z. B. für eine künftige Debug-UI. */
    @Synchronized
    fun snapshot(): List<LogLine> = buffer.toList()

    companion object {
        const val DEFAULT_CAPACITY = 500
    }
}

/** Ein einzelner gepufferter Log-Eintrag. */
data class LogLine(
    val timestampMillis: Long,
    val priority: Int,
    val tag: String?,
    val message: String,
)

/**
 * Simples längenpräfigiertes Binärformat (analog `EnvelopeFile`s Nonce-Präfix, Konzept 6a: kein
 * CBOR für eine einfache, feste Feldliste nötig): `[4 Byte Anzahl][je Eintrag: 8 Byte Timestamp,
 * 4 Byte Priorität, 1 Byte Tag-Vorhanden-Flag, optional UTF-Tag, UTF-Message]`. `writeUTF`/
 * `readUTF` (Java-Modified-UTF-8) begrenzen Tag/Message auf je 65535 Byte kodierte Länge — für
 * Log-Zeilen in der Praxis nie relevant, aber bewusst kein Fallback dafür eingebaut.
 */
internal fun encodeLines(lines: Collection<LogLine>): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use { data ->
        data.writeInt(lines.size)
        for (line in lines) {
            data.writeLong(line.timestampMillis)
            data.writeInt(line.priority)
            data.writeBoolean(line.tag != null)
            if (line.tag != null) data.writeUTF(line.tag)
            data.writeUTF(line.message)
        }
    }
    return out.toByteArray()
}

internal fun decodeLines(bytes: ByteArray): List<LogLine> {
    val data = DataInputStream(bytes.inputStream())
    val count = data.readInt()
    return buildList {
        repeat(count) {
            val timestamp = data.readLong()
            val priority = data.readInt()
            val hasTag = data.readBoolean()
            val tag = if (hasTag) data.readUTF() else null
            val message = data.readUTF()
            add(LogLine(timestamp, priority, tag, message))
        }
    }
}
