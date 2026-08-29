package de.ble1st.warden.logging

import android.util.Log
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.domain.logging.HashChainAnchorCodec
import de.ble1st.warden.domain.logging.HashChainRetentionDecision
import de.ble1st.warden.domain.logging.HashChainWipeGuardDecision
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
 *
 * **Wipe-Guard (optionaler zweiter Anker, analog [de.ble1st.warden.domain.pin
 * .WardenPinReplayDecision]):** die Kette selbst erkennt nur *interne* Inkonsistenz zwischen
 * vorhandenen Einträgen — werden ganze Archiv-Segmente oder das aktive Segment sauber gelöscht
 * (nicht nur beschädigt), bleibt eine intern konsistente, nur kürzere Kette übrig, die
 * [verifyChainIntegrity] allein nicht von "es wurde nie geloggt" unterscheiden kann (eine leere
 * Kette verifiziert trivial als `Valid(0)`). [wipeGuardAnchorFile] — falls übergeben — merkt sich
 * nach jedem [append] Sequenznummer + Hash des letzten Eintrags in einer eigenen, eigenständig
 * KEK-gewrappten Datei; [verifyChainIntegrity] vergleicht dagegen und meldet `Broken`, wenn die
 * Kette hinter diesem Anker zurückfällt. `null` (Default) deaktiviert die Prüfung vollständig —
 * bestehende Aufrufer/Tests ohne Anker verhalten sich unverändert wie vor dieser Ergänzung.
 *
 * **Aufbewahrungsgrenze (2026-08-28, aus der Code-/Sicherheitsanalyse, Befund Q-3):** die
 * Rotation oben machte [append] billig, aber die Gesamtmenge wuchs weiterhin unbegrenzt —
 * archivierte Segmente wurden nie verworfen, während [entries]/[verifyChainIntegrity] jedes davon
 * lesen und entschlüsseln. Sind [keepArchivedSegments] **und** [retentionAnchorFile] gesetzt,
 * werden nach einer Rotation die ältesten Segmente über diese Grenze hinaus gelöscht.
 *
 * Das ist bewusst nicht als schlichtes Löschen gebaut: eine gekürzte Kette ist von außen nicht
 * von einer *manipulierten* zu unterscheiden — der erste verbliebene Eintrag zeigt nicht mehr auf
 * [GENESIS_HASH]. Deshalb hält [retentionAnchorFile] Sequenznummer und Hash des zuletzt
 * verworfenen Eintrags fest, und [de.ble1st.warden.domain.logging.HashChainRetentionDecision]
 * entscheidet daraus, welcher Starthash für die verbliebene Kette gilt. Eine Kürzung **ohne**
 * passenden Anker bleibt damit unverändert als `Broken` erkennbar; der Anker erklärt genau die
 * eine Lücke, die Warden selbst erzeugt hat, und keine andere.
 *
 * Reihenfolge beim Verwerfen: erst der Anker, dann das Löschen. Ein Absturz dazwischen lässt
 * Dateien stehen, die der Anker bereits als verworfen führt — die Kette beginnt dann weiterhin bei
 * Sequenz 0 und verifiziert trotzdem (s. [HashChainRetentionDecision.startOf][de.ble1st.warden
 * .domain.logging.HashChainRetentionDecision.startOf]). Andersherum entstünde eine Lücke, für die
 * kein Anker existiert — also ein falscher Manipulationsalarm.
 *
 * Ohne beide Parameter (Default) wird nichts verworfen; das bisherige Verhalten bleibt exakt
 * erhalten.
 */
class HashChainLogStore(
    private val envelopeFile: EnvelopeFile,
    private val segmentCapacity: Int = DEFAULT_SEGMENT_CAPACITY,
    private val wipeGuardAnchorFile: EnvelopeFile? = null,
    private val retentionAnchorFile: EnvelopeFile? = null,
    private val keepArchivedSegments: Int? = null,
) {
    init {
        require(segmentCapacity > 0) { "segmentCapacity muss positiv sein: $segmentCapacity" }
        require(keepArchivedSegments == null || keepArchivedSegments > 0) {
            "keepArchivedSegments muss positiv sein: $keepArchivedSegments"
        }
        require(keepArchivedSegments == null || retentionAnchorFile != null) {
            "Aufbewahrungsgrenze ohne Retention-Anker würde die Kettenprüfung brechen — s. Klassendoc"
        }
    }

    private val archiveBaseName: String = envelopeFile.dataFile.nameWithoutExtension
    private val archiveExtension: String = envelopeFile.dataFile.extension.let { if (it.isEmpty()) "" else ".$it" }

    @Synchronized
    fun append(priority: Int, tag: String?, message: String): LogEntry {
        var activeChain = loadActiveChain()
        if (activeChain.size >= segmentCapacity) {
            archiveActiveSegment(activeChain)
            activeChain = emptyList()
            discardArchivesBeyondRetention()
        }

        val (previousHash, sequence) = tailOf(activeChain)
        val timestamp = System.currentTimeMillis()
        val hash = computeHash(previousHash, sequence, timestamp, priority, tag, message)
        val entry = LogEntry(sequence, timestamp, priority, tag, message, previousHash, hash)

        envelopeFile.write(encodeEntries(activeChain + entry))
        // Strictly after the data write above (s. Klassendoc "Wipe-Guard") — a crash between the
        // two can only leave the anchor behind the chain, never ahead of it.
        wipeGuardAnchorFile?.write(HashChainAnchorCodec.encode(entry.sequence, entry.hash))
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

    /** Prüft die gesamte Kette (alle Segmente) auf Konsistenz — s. Klassendoc — und, falls ein
     * [wipeGuardAnchorFile] übergeben wurde, zusätzlich gegen den Wipe-Guard-Anker. */
    fun verifyChainIntegrity(): ChainVerificationResult = verifyLoadedChain(entries())

    /**
     * Wie [verifyChainIntegrity], aber über eine bereits geladene Kette (2026-08-28, Befund Q-3):
     * die Log-Einsicht brauchte die Einträge ohnehin und ließ sie durch [verifyChainIntegrity]
     * ein zweites Mal von der Platte lesen und entschlüsseln — bei mehreren Segmenten der
     * teuerste Teil des ganzen Vorgangs, doppelt.
     *
     * [chain] muss die **vollständige** Kette sein (also das Ergebnis von [entries]): sowohl der
     * Wipe-Guard als auch die Retention-Prüfung vergleichen gegen deren Enden. Eine gefilterte
     * Teilliste würde beide fälschlich `Broken` melden.
     */
    fun verifyLoadedChain(chain: List<LogEntry>): ChainVerificationResult {
        val start = expectedStartHash(chain)
        if (start is HashChainRetentionDecision.Start.Unexplained) {
            return ChainVerificationResult.Broken(chain.firstOrNull()?.sequence ?: -1L, "retention: ${start.reason}")
        }
        val startHash = (start as? HashChainRetentionDecision.Start.AfterDiscarded)?.hash ?: GENESIS_HASH
        val structural = verifyChainIntegrity(chain, startHash)
        if (structural is ChainVerificationResult.Broken) return structural
        return checkWipeGuard(chain) ?: structural
    }

    /**
     * Sequenznummer des zuletzt durch die Aufbewahrungsgrenze verworfenen Eintrags, oder `null`,
     * wenn nie etwas verworfen wurde. Für die Log-Einsicht: dass die Ansicht nicht bei Sequenz 0
     * beginnt, soll dort als Aufbewahrungsgrenze erklärt dastehen und nicht als fehlender Anfang.
     */
    fun discardedThroughSequence(): Long? {
        val anchorFile = retentionAnchorFile ?: return null
        if (!anchorFile.hasStorage()) return null
        return HashChainAnchorCodec.decode(anchorFile.read()).first
    }

    /** Erwarteter Starthash der verbliebenen Kette — s. [HashChainRetentionDecision]. */
    private fun expectedStartHash(chain: List<LogEntry>): HashChainRetentionDecision.Start {
        val anchorFile = retentionAnchorFile
        val anchorPresent = anchorFile != null && anchorFile.hasStorage()
        val (anchorSequence, anchorHash) = if (anchorPresent) {
            HashChainAnchorCodec.decode(anchorFile.read())
        } else {
            0L to ByteArray(0)
        }
        return HashChainRetentionDecision.startOf(
            chainPresent = chain.isNotEmpty(),
            firstSequence = chain.firstOrNull()?.sequence ?: 0L,
            anchorPresent = anchorPresent,
            anchorSequence = anchorSequence,
            anchorHash = anchorHash,
        )
    }

    /**
     * Verwirft die ältesten Archivsegmente über [keepArchivedSegments] hinaus und schreibt vorher
     * den Retention-Anker — s. Klassendoc für die Reihenfolge und warum sie so herum sein muss.
     * Ohne konfigurierte Grenze passiert hier nichts.
     */
    private fun discardArchivesBeyondRetention() {
        val anchorFile = retentionAnchorFile ?: return
        val files = archiveFiles()
        val discardCount = HashChainRetentionDecision.segmentsToDiscard(files.size, keepArchivedSegments)
        if (discardCount == 0) return

        val discard = files.take(discardCount)
        val lastDiscarded = decodeEntries(envelopeFile.sibling(discard.last().name).read()).last()
        anchorFile.write(HashChainAnchorCodec.encode(lastDiscarded.sequence, lastDiscarded.hash))
        for (file in discard) {
            if (!file.delete()) {
                // Kein Abbruch: der Anker steht bereits, und ein liegengebliebenes Segment ist der
                // harmlose Fall (die Kette beginnt dann weiter vorn und verifiziert trotzdem).
                // Beim nächsten Lauf wird es erneut versucht.
                Log.w(TAG, "Archivsegment ${file.name} nicht löschbar")
            }
        }
        Log.i(
            TAG,
            "Aufbewahrungsgrenze: $discardCount Archivsegment(e) verworfen, " +
                "Einträge bis Sequenz ${lastDiscarded.sequence} sind nicht mehr einsehbar",
        )
    }

    /** `null`, solange kein Anker konfiguriert ist oder die Kette ihn (noch) einhält — sonst ein
     * `Broken`-Ergebnis mit der Wipe-Guard-Begründung als `reason`. Rein lesend: der Anker selbst
     * wird ausschließlich in [append] fortgeschrieben, nie hier (s. Klassendoc). */
    private fun checkWipeGuard(chain: List<LogEntry>): ChainVerificationResult.Broken? {
        val anchorFile = wipeGuardAnchorFile ?: return null
        val anchorPresent = anchorFile.hasStorage()
        val (anchorSequence, anchorHash) = if (anchorPresent) {
            HashChainAnchorCodec.decode(anchorFile.read())
        } else {
            0L to ByteArray(0)
        }
        val tail = chain.lastOrNull()
        val decision = HashChainWipeGuardDecision.evaluate(
            chainPresent = tail != null,
            chainTailSequence = tail?.sequence ?: -1L,
            chainTailHash = tail?.hash ?: ByteArray(0),
            anchorPresent = anchorPresent,
            anchorSequence = anchorSequence,
            anchorHash = anchorHash,
        )
        return (decision as? HashChainWipeGuardDecision.Result.Reject)?.let {
            ChainVerificationResult.Broken(tail?.sequence ?: -1L, "wipe guard: ${it.reason}")
        }
    }

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
        private const val TAG = "HashChainLogStore"

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
 *
 * [startHash] ist normalerweise [HashChainLogStore.GENESIS_HASH]; wurde der Anfang der Kette durch
 * die Aufbewahrungsgrenze verworfen, steht dort der Hash des zuletzt verworfenen Eintrags (s.
 * [de.ble1st.warden.domain.logging.HashChainRetentionDecision]). Der Default hält alle bisherigen
 * Aufrufer unverändert.
 */
internal fun verifyChainIntegrity(
    chain: List<LogEntry>,
    startHash: ByteArray = HashChainLogStore.GENESIS_HASH,
): ChainVerificationResult {
    var expectedPrevious = startHash
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
