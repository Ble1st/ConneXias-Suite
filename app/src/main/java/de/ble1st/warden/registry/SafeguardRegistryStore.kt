package de.ble1st.warden.registry

import de.ble1st.warden.crypto.EnvelopeFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Meilenstein C.3 (Konzept 19): "Registry-Persistenz (Soll-Zustand, über Envelope B.4)".
 * Verschlüsselte Persistenz für [de.ble1st.warden.domain.registry.SafeguardRegistry]s
 * Soll-Zustand (`id -> Boolean`) über [EnvelopeFile] (B.4, seit B.6 mit atomarem Write). Kennt
 * `SafeguardRegistry` selbst nicht — arbeitet bewusst nur mit der reinen `Map<String, Boolean>`
 * (s. `SafeguardRegistry.desiredStateSnapshot`/`restoreDesiredState`), leichter testbar und ohne
 * Kopplung an die Registry-Klasse selbst. [PersistentSafeguardRegistry] verdrahtet beides.
 *
 * **Prozessweite Sperre um den Read-Modify-Write-Zyklus (Befund Q-7, 2026-08-29):** [save] liest,
 * merged und schreibt. Der *Write* allein ist zwar atomar (`EnvelopeFile` nutzt
 * `Files.move(ATOMIC_MOVE)`), der Zyklus darum herum war es nicht — und es gibt fünf unabhängig
 * konstruierte [PersistentSafeguardRegistry]-Instanzen (ConcordBus, Boot-Receiver,
 * `SensitiveActionActivity`, `SentinelInstallResultReceiver`, Watchdog-Controller), deren Läufe
 * sich zeitlich überlappen können. Ohne Sperre ging eine gleichzeitige Änderung verloren
 * (klassisches Lost Update: beide lesen denselben Stand, der zweite Write überschreibt den ersten).
 * Der Schaden blieb klein, weil `RegistryReconciler` bei nächster Gelegenheit korrigiert — aber
 * "wird später eh repariert" ist kein Grund, einen bekannten Datenverlust stehen zu lassen.
 *
 * Die Sperre liegt im Companion-Object, ist also **prozessweit** und nicht pro Instanz — genau das
 * ist der Punkt: alle Instanzen zeigen auf dieselbe Envelope-Datei
 * ([de.ble1st.warden.registry.RegistryStorage]), eine Instanz-Sperre würde exakt nichts
 * serialisieren. Dass damit auch Stores auf *anderen* Dateien (isolierte Instrumented-Tests)
 * mitserialisiert werden, ist bewusst in Kauf genommen: die Alternative wäre eine Lock-Tabelle pro
 * Pfad, und die Schreibvorgänge sind selten und kurz genug, dass der Unterschied nie messbar wird.
 */
class SafeguardRegistryStore(private val envelopeFile: EnvelopeFile) {

    /** Zuletzt gespeicherter Soll-Zustand, oder eine leere Map, wenn noch nie gespeichert wurde.
     * Fail-Safe (Invariante 6): existiert bereits ein gewrappter DEK **oder** eine Datendatei,
     * aber das Lesen scheitert, wirft [EnvelopeFile.read] statt still eine leere Map zu liefern — ein
     * verschwundener Soll-Zustand darf nie als "noch nie gesetzt" durchgehen. */
    fun load(): Map<String, Boolean> = synchronized(WRITE_LOCK) { loadUnlocked() }

    fun save(desiredState: Map<String, Boolean>) = synchronized(WRITE_LOCK) {
        // Overlay, never replace: a caller that only registered a subset (or omits lockdown
        // on purpose) must not wipe other IDs from the shared envelope.
        val merged = loadUnlocked() + desiredState
        envelopeFile.write(encodeDesiredState(merged))
    }

    /** Ohne Sperre — nur aus bereits gesperrtem Kontext aufrufen (`synchronized` ist in Kotlin/JVM
     * reentrant, ein direkter Aufruf von [load] hier wäre also ebenfalls korrekt; getrennt gehalten,
     * damit an der Aufrufstelle sichtbar bleibt, dass der Merge *innerhalb* der Sperre passiert). */
    private fun loadUnlocked(): Map<String, Boolean> =
        if (envelopeFile.hasStorage()) decodeDesiredState(envelopeFile.read()) else emptyMap()

    private companion object {
        /** S. Klassendoc: bewusst prozessweit (Companion), nicht pro Instanz. */
        val WRITE_LOCK = Any()
    }
}

/**
 * Storage-Format, analog zu B.5/B.6s längenpräfigierten Formaten (kein CBOR für ein so
 * einfaches Schema nötig): `[4 Byte Anzahl][je Eintrag: UTF-id, 1 Byte Boolean]`.
 */
internal fun encodeDesiredState(state: Map<String, Boolean>): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use { data ->
        data.writeInt(state.size)
        for ((id, desired) in state) {
            data.writeUTF(id)
            data.writeBoolean(desired)
        }
    }
    return out.toByteArray()
}

internal fun decodeDesiredState(bytes: ByteArray): Map<String, Boolean> {
    val data = DataInputStream(bytes.inputStream())
    val count = data.readInt()
    return buildMap {
        repeat(count) {
            val id = data.readUTF()
            val desired = data.readBoolean()
            put(id, desired)
        }
    }
}
