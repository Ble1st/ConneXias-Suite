package de.ble1st.warden.logging

import de.ble1st.warden.crypto.EnvelopeFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Meilenstein B.6 (Konzept 19): "Zentraler append-only Log-Store in Warden mit Hash-Kette;
 * Manipulationstest (Eintrag entfernen → Kette bricht → erkannt)".
 *
 * Jeder Eintrag referenziert per SHA-256-Hash seinen Vorgänger (Konzept Abschnitt 2b/(5):
 * "Hash-Kette statt nur Zähler" — dort für den Rollback-Schutz-Blob beschrieben, hier auf den
 * Log-Store angewendet): `hash = SHA-256(previousHash ∥ sequence ∥ timestamp ∥ priority ∥ tag ∥
 * message)`. Entfernen, Einfügen oder Verändern eines Eintrags bricht die Kette ab dieser Stelle
 * nachweisbar — siehe [verifyChainIntegrity].
 *
 * **Ehrliche Grenze (Konzept 2b/(7), CLAUDE.md „Herald-Isolation: dauerhaft eine APK"):** Die
 * Kette erkennt zuverlässig externe Manipulation ohne Kenntnis der Verkettungsregel
 * (Root/Forensik ohne App-UID, Threat Model P8/7.1). Sie schützt **nicht** gegen einen
 * kompromittierten, gleich-UID-Herald-Prozess — der hat i. d. R. denselben Datei-/Keystore-
 * Zugriff und kann eine komplette, intern konsistente Kette neu berechnen. Das ist der dauerhaft
 * akzeptierte Preis der Ein-APK-Architektur (siehe Konzept 2b/(7) Punkt 3), keine Lücke in
 * dieser Implementierung.
 *
 * **Segment-Rotation (Stabilisierung nach G):** physisch **kein** unbegrenztes Ein-Datei-Modell
 * mehr. [append] liest/versiegelt nur noch das *aktive* Segment (höchstens [segmentCapacity]
 * Einträge) statt bei jedem einzelnen Log-Eintrag die *gesamte* bisherige Historie neu zu
 * verschlüsseln und zu schreiben — vorher O(n) Arbeit pro Aufruf, damit O(n²) über die
 * Laufzeit, "wichtigster rein-technischer Punkt für echten Langzeitbetrieb" laut Projektstand.
 * Erreicht das aktive Segment [segmentCapacity] Einträge, wird es unter einem deterministischen,
 * aus der letzten Sequenznummer abgeleiteten Dateinamen archiviert (`EnvelopeFile.sibling`,
 * derselbe gewrappte DEK, kein Re-Wrap) und durch ein frisches, leeres aktives Segment ersetzt.
 * Archivierte Segmente werden **nie** wieder verändert oder neu geschrieben — "append-only"
 * bleibt append-only, kein Eintrag geht verloren, [entries]/[verifyChainIntegrity] lesen weiterhin
 * die volle Historie (archivierte Segmente + aktives Segment), das ist weiterhin O(n) — nur
 * [append] selbst wird dadurch unabhängig von der Gesamtgröße billig.
 *
 * **Crash-sicher, ohne die Fail-Safe-Garantie von [entries]/[verifyChainIntegrity] zu
 * schwächen:** Rotation ist zwei einzeln atomare `EnvelopeFile.write()`-Aufrufe (Archiv
 * schreiben, dann aktives Segment auf leer setzen) — nicht gemeinsam atomar, aber **idempotent**:
 * der Archivname ist deterministisch aus dem letzten archivierten Eintrag abgeleitet, ein
 * Absturz zwischen beiden Schritten führt beim nächsten [append] dazu, dass der (bereits
 * vorhandene) Archiv-Schreibvorgang übersprungen und nur das Leeren nachgeholt wird — nie
 * doppelte oder verlorene Einträge. Das aktive Segment existiert dabei **immer** als Datei,
 * sobald ein DEK existiert (nie kommissarisch "fehlend") — die ursprüngliche B.6-Garantie
 * "fehlende Datenfile trotz existierendem DEK ist immer ein Fehler, nie ein leerer Zustand"
 * (Invariante 6) gilt für das aktive Segment unverändert weiter.
 */
class HashChainLogStore(
    private val envelopeFile: EnvelopeFile,
    private val segmentCapacity: Int = DEFAULT_SEGMENT_CAPACITY,
) {
    init {
        require(segmentCapacity > 0) { "segmentCapacity muss positiv sein: $segmentCapacity" }
    }

    private val archiveBaseName: String = envelopeFile.dataFile.nameWithoutExtension
    private val archiveExtension: String = envelopeFile.dataFile.extension.let { if (it.isEmpty()) "" else ".$it" }

    @Synchronized
    fun append(priority: Int, tag: String?, message: String): LogEntry {
        var activeChain = loadActiveChain()
        if (activeChain.size >= segmentCapacity) {
            archiveActiveSegment(activeChain)
            activeChain = emptyList()
        }

        val (previousHash, sequence) = tailOf(activeChain)
        val timestamp = System.currentTimeMillis()
        val hash = computeHash(previousHash, sequence, timestamp, priority, tag, message)
        val entry = LogEntry(sequence, timestamp, priority, tag, message, previousHash, hash)

        envelopeFile.write(encodeEntries(activeChain + entry))
        return entry
    }

    /**
     * Vollständige Kette, älteste zuerst — archivierte Segmente + aktives Segment. Fail-Safe
     * (Invariante 6): anders als [LocalRingTree] ist dies der sicherheitsrelevante Log-Store —
     * existiert bereits ein gewrappter DEK, aber eine Segment-Datei fehlt/ist unlesbar (z. B.
     * gelöscht, um Einträge verschwinden zu lassen), wirft [EnvelopeFile.read] statt still eine
     * leere/verkürzte Kette zu liefern.
     */
    fun entries(): List<LogEntry> = archivedEntries() + loadActiveChain()

    /** Prüft die gesamte Kette (alle Segmente) auf Konsistenz — s. Klassendoc. */
    fun verifyChainIntegrity(): ChainVerificationResult = verifyChainIntegrity(entries())

    private fun loadActiveChain(): List<LogEntry> =
        if (envelopeFile.hasStorage()) decodeEntries(envelopeFile.read()) else emptyList()

    private fun archivedEntries(): List<LogEntry> =
        archiveFiles().flatMap { file -> decodeEntries(envelopeFile.sibling(file.name).read()) }

    /**
     * previousHash + nächste Sequenznummer für den nächsten Eintrag — global monoton über alle
     * Segmente hinweg (Konzept 2b/(6): "monotoner Zähler"), nicht nur innerhalb des aktiven
     * Segments. Braucht dafür **nicht** die komplette Historie zu laden: das aktive Segment kennt
     * seinen eigenen letzten Eintrag; ist es (frisch nach einer Rotation) leer, genügt der letzte
     * Eintrag des zuletzt archivierten Segments — beides höchstens [segmentCapacity] Einträge,
     * nie die volle Kette.
     */
    private fun tailOf(activeChain: List<LogEntry>): Pair<ByteArray, Long> {
        activeChain.lastOrNull()?.let { return it.hash to it.sequence + 1 }
        val lastArchive = archiveFiles().lastOrNull() ?: return GENESIS_HASH to 0L
        val last = decodeEntries(envelopeFile.sibling(lastArchive.name).read()).last()
        return last.hash to last.sequence + 1
    }

    /**
     * Archiviert das volle aktive Segment und setzt das aktive Segment danach auf leer zurück —
     * s. Klassendoc für die Crash-/Idempotenz-Garantie. Der Archivname ist deterministisch aus
     * `chain.last().sequence` abgeleitet: ein Absturz-Retry mit identischem `chain`-Inhalt
     * erzeugt denselben Namen und überspringt den (bereits vorhandenen) Archiv-Schreibvorgang,
     * statt eine zweite, teilweise überlappende Archivdatei anzulegen.
     */
    private fun archiveActiveSegment(chain: List<LogEntry>) {
        val archiveFile = File(envelopeFile.dataFile.parentFile, archiveFileName(chain.last().sequence))
        if (!archiveFile.exists()) {
            envelopeFile.sibling(archiveFile.name).write(encodeEntries(chain))
        }
        envelopeFile.write(encodeEntries(emptyList()))
    }

    private fun archiveFileName(lastSequence: Long): String = archiveFileName(archiveBaseName, archiveExtension, lastSequence)

    /** Alle vorhandenen Archiv-Dateien dieses Stores, älteste zuerst (Namen sind durch das
     * Nullpadding in [archiveFileName] lexikografisch identisch zur numerischen Ordnung). */
    private fun archiveFiles(): List<File> {
        val dir = envelopeFile.dataFile.parentFile ?: return emptyList()
        val prefix = "$archiveBaseName.archive."
        return (dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(archiveExtension) } ?: emptyArray())
            .sortedBy { it.name }
    }

    companion object {
        /** Anzahl Einträge, ab der ein Segment archiviert und durch ein frisches ersetzt wird. */
        const val DEFAULT_SEGMENT_CAPACITY = 500

        /**
         * Hash-Anker für den ersten Eintrag — fixe Domain-Separation statt eines
         * All-Null-Werts, damit ein absichtlich (oder durch Bug) all-genullter `previousHash`
         * nie unbemerkt als "das ist der Kettenanfang" durchgeht.
         */
        val GENESIS_HASH: ByteArray =
            MessageDigest.getInstance("SHA-256").digest("warden:log:genesis:v1".toByteArray())
    }
}

/** Ein einzelner, hash-verketteter Log-Eintrag. */
data class LogEntry(
    val sequence: Long,
    val timestampMillis: Long,
    val priority: Int,
    val tag: String?,
    val message: String,
    val previousHash: ByteArray,
    val hash: ByteArray,
) {
    // Kotlins generiertes data-class-equals/hashCode vergleicht ByteArray-Felder per Referenz,
    // nicht per Inhalt (bekannte Stolperfalle) — hier explizit auf contentEquals/
    // contentHashCode umgestellt, sonst schlagen Tests mit strukturell gleichen, aber
    // unterschiedlichen Array-Instanzen unerwartet fehl.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LogEntry) return false
        return sequence == other.sequence &&
            timestampMillis == other.timestampMillis &&
            priority == other.priority &&
            tag == other.tag &&
            message == other.message &&
            previousHash.contentEquals(other.previousHash) &&
            hash.contentEquals(other.hash)
    }

    override fun hashCode(): Int {
        var result = sequence.hashCode()
        result = 31 * result + timestampMillis.hashCode()
        result = 31 * result + priority
        result = 31 * result + (tag?.hashCode() ?: 0)
        result = 31 * result + message.hashCode()
        result = 31 * result + previousHash.contentHashCode()
        result = 31 * result + hash.contentHashCode()
        return result
    }
}

/** Ergebnis von [HashChainLogStore.verifyChainIntegrity] bzw. der reinen [verifyChainIntegrity]-Funktion. */
sealed class ChainVerificationResult {
    data class Valid(val entryCount: Long) : ChainVerificationResult()
    data class Broken(val atSequence: Long, val reason: String) : ChainVerificationResult()
}

/**
 * Reine, Android-freie Verkettungsprüfung über eine bereits geladene Liste — getrennt von der
 * `EnvelopeFile`-Persistenz, damit die eigentliche Manipulationserkennung (Meilenstein-B.6-
 * Abnahmekriterium: "Eintrag entfernen → Kette bricht → erkannt") als schneller JVM-Unit-Test
 * ohne Gerät/Keystore prüfbar ist.
 */
internal fun verifyChainIntegrity(chain: List<LogEntry>): ChainVerificationResult {
    var expectedPrevious = HashChainLogStore.GENESIS_HASH
    for (entry in chain) {
        if (!entry.previousHash.contentEquals(expectedPrevious)) {
            return ChainVerificationResult.Broken(
                entry.sequence,
                "previousHash zeigt nicht auf den tatsächlichen Vorgänger",
            )
        }
        val recomputed = computeHash(
            entry.previousHash,
            entry.sequence,
            entry.timestampMillis,
            entry.priority,
            entry.tag,
            entry.message,
        )
        if (!entry.hash.contentEquals(recomputed)) {
            return ChainVerificationResult.Broken(
                entry.sequence,
                "gespeicherter Hash passt nicht zum Inhalt des Eintrags",
            )
        }
        expectedPrevious = entry.hash
    }
    return ChainVerificationResult.Valid(chain.size.toLong())
}

internal fun computeHash(
    previousHash: ByteArray,
    sequence: Long,
    timestampMillis: Long,
    priority: Int,
    tag: String?,
    message: String,
): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(previousHash)
    digest.update(encodeEntryFields(sequence, timestampMillis, priority, tag, message))
    return digest.digest()
}

/** Serialisiert die inhaltlichen Felder eines Eintrags (ohne `previousHash`/`hash` selbst) —
 * das ist genau das, was der Hash zusätzlich zu `previousHash` abdeckt. */
internal fun encodeEntryFields(
    sequence: Long,
    timestampMillis: Long,
    priority: Int,
    tag: String?,
    message: String,
): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use { data ->
        data.writeLong(sequence)
        data.writeLong(timestampMillis)
        data.writeInt(priority)
        data.writeBoolean(tag != null)
        if (tag != null) data.writeUTF(tag)
        data.writeUTF(message)
    }
    return out.toByteArray()
}

/**
 * Storage-Format: `[4 Byte Anzahl][je Eintrag: Feldbytes (s. [encodeEntryFields], selbstbe-
 * schreibend dank `writeUTF`-Längenpräfix) ∥ 32 Byte previousHash ∥ 32 Byte hash]`. Die beiden
 * Hashes brauchen kein Längenpräfix — SHA-256-Ausgabe hat immer exakt 32 Byte.
 */
internal fun encodeEntries(entries: List<LogEntry>): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use { data ->
        data.writeInt(entries.size)
        for (entry in entries) {
            data.write(
                encodeEntryFields(
                    entry.sequence,
                    entry.timestampMillis,
                    entry.priority,
                    entry.tag,
                    entry.message,
                ),
            )
            data.write(entry.previousHash)
            data.write(entry.hash)
        }
    }
    return out.toByteArray()
}

internal fun decodeEntries(bytes: ByteArray): List<LogEntry> {
    val data = DataInputStream(bytes.inputStream())
    val count = data.readInt()
    return buildList {
        repeat(count) {
            val sequence = data.readLong()
            val timestamp = data.readLong()
            val priority = data.readInt()
            val hasTag = data.readBoolean()
            val tag = if (hasTag) data.readUTF() else null
            val message = data.readUTF()
            val previousHash = ByteArray(HASH_LENGTH_BYTES).also { data.readFully(it) }
            val hash = ByteArray(HASH_LENGTH_BYTES).also { data.readFully(it) }
            add(LogEntry(sequence, timestamp, priority, tag, message, previousHash, hash))
        }
    }
}

private const val HASH_LENGTH_BYTES = 32

/**
 * Deterministischer Archiv-Dateiname für ein rotiertes Segment, dessen letzter Eintrag die
 * Sequenznummer [lastSequence] trägt — reine, gerätefreie Funktion (JVM-testbar, s.
 * `HashChainIntegrityTest`), getrennt von [HashChainLogStore.archiveActiveSegment]s eigentlicher
 * Datei-I/O. 19 Stellen Nullpadding reichen für jeden `Long`-Wert (`Long.MAX_VALUE` hat 19
 * Ziffern) und garantieren, dass lexikografische und numerische Sortierung übereinstimmen.
 */
internal fun archiveFileName(baseName: String, extension: String, lastSequence: Long): String =
    "$baseName.archive.${lastSequence.toString().padStart(19, '0')}$extension"
