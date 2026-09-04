package de.ble1st.warden.domain.logging

/**
 * "Aufbewahrungsgrenze fürs Audit-Log" (2026-08-28, aus der Code-/Sicherheitsanalyse, Befund
 * Q-3) — reine, framework-freie Entscheidung dazu, wie eine *absichtlich* gekürzte Hash-Kette
 * von einer *manipulierten* unterschieden wird.
 *
 * **Warum das nicht trivial ist:** `HashChainLogStore` sammelte bisher jedes je geschriebene
 * Archivsegment ein und löschte nie eines. Einfach die ältesten zu verwerfen wäre aber genau
 * das, was die Kettenprüfung als Angriff erkennen *soll*: der erste verbliebene Eintrag zeigt
 * dann nicht mehr auf den Genesis-Hash, und `verifyChainIntegrity` meldete `Broken` — die
 * Aufbewahrungsgrenze hätte den Manipulationsnachweis dauerhaft zerstört.
 *
 * Deshalb hinterlässt das Verwerfen einen eigenständig verschlüsselten **Retention-Anker**
 * (Sequenznummer + Hash des zuletzt verworfenen Eintrags, dasselbe [HashChainAnchorCodec]-Format
 * wie beim Wipe-Guard) und diese Entscheidung sagt, welcher Starthash für die verbliebene Kette
 * zu erwarten ist. Eine Kürzung ohne passenden Anker bleibt damit unverändert erkennbar — der
 * Anker erklärt genau eine Lücke, nämlich die, die Warden selbst erzeugt hat.
 *
 * Der Anker ist **kein** Ersatz für die verworfenen Einträge und wird auch nicht als solcher
 * ausgegeben: die Inhalte sind weg, nachweisbar bleibt nur, dass sie bis Sequenz N existiert
 * haben und die Kette lückenlos daran anschließt.
 */
object HashChainRetentionDecision {

    /** Erwarteter `previousHash` des ersten verbliebenen Eintrags. */
    sealed class Start {
        /** Die Kette beginnt am Anfang (oder ist leer) — Genesis-Hash erwarten. */
        data object Genesis : Start()

        /** Die Kette beginnt hinter einer verworfenen Strecke — [hash] erwarten. */
        data class AfterDiscarded(val hash: ByteArray) : Start() {
            // Wie bei LogEntry: data-class-equals vergleicht ByteArray sonst per Referenz.
            override fun equals(other: Any?): Boolean =
                this === other || (other is AfterDiscarded && hash.contentEquals(other.hash))

            override fun hashCode(): Int = hash.contentHashCode()
        }

        /** Die Kette beginnt mitten drin, ohne dass ein Anker das erklärt — Manipulationsverdacht. */
        data class Unexplained(val reason: String) : Start()
    }

    /**
     * @param chainPresent ob überhaupt Einträge vorhanden sind.
     * @param firstSequence Sequenznummer des ersten verbliebenen Eintrags (egal, wenn
     * `chainPresent == false`).
     * @param anchorPresent ob ein Retention-Anker hinterlegt ist.
     * @param anchorSequence Sequenznummer des zuletzt verworfenen Eintrags.
     * @param anchorHash dessen Hash.
     */
    fun startOf(
        chainPresent: Boolean,
        firstSequence: Long,
        anchorPresent: Boolean,
        anchorSequence: Long,
        anchorHash: ByteArray,
    ): Start {
        if (!chainPresent) return Start.Genesis
        // Eine Kette, die bei 0 beginnt, ist ungekürzt — auch dann, wenn ein Anker existiert.
        // Genau dieser Fall tritt nach einem Absturz zwischen "Anker geschrieben" und "Dateien
        // gelöscht" auf (s. HashChainLogStore.pruneArchives): die Dateien sind noch da, der Anker
        // läuft der Wirklichkeit voraus. Das darf keine Broken-Meldung geben.
        if (firstSequence == 0L) return Start.Genesis
        if (!anchorPresent) {
            return Start.Unexplained(
                "Kette beginnt bei Sequenz $firstSequence, aber kein Retention-Anker erklärt die Lücke",
            )
        }
        if (anchorSequence + 1 != firstSequence) {
            return Start.Unexplained(
                "Retention-Anker endet bei Sequenz $anchorSequence, die Kette beginnt aber bei $firstSequence",
            )
        }
        return Start.AfterDiscarded(anchorHash)
    }

    /**
     * Wie viele der ältesten Archivsegmente verworfen werden dürfen. `0`, solange die Grenze nicht
     * überschritten ist; `null` als [keepSegments] heißt "keine Grenze" (bisheriges Verhalten).
     *
     * Das aktive Segment zählt bewusst **nicht** mit: es enthält die jüngsten Einträge und wird
     * nie verworfen.
     */
    fun segmentsToDiscard(archivedSegmentCount: Int, keepSegments: Int?): Int {
        if (keepSegments == null) return 0
        require(keepSegments > 0) { "keepSegments muss positiv sein: $keepSegments" }
        return (archivedSegmentCount - keepSegments).coerceAtLeast(0)
    }
}
