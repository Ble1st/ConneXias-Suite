package de.ble1st.warden.clipboard

import android.content.Context
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import java.io.File

/**
 * Envelope-Datei-Aufbau für [ClipboardAccessEventStore] — dasselbe Muster wie
 * `de.ble1st.warden.logging.SecurityEventStorage`, mit einem Unterschied: **normaler
 * (credential-verschlüsselter) Speicher, nicht Device-Protected.** `SecurityEventStorage` braucht
 * Device-Protected Storage, weil DPM-Batches auch vor dem ersten Entsperren nach einem Neustart
 * eintreffen können — ein `AccessibilityService` läuft dagegen erst innerhalb einer echten
 * Nutzersitzung, also nie vor dem ersten Entsperren (s. `de.ble1st.warden.clipboard
 * .ClipboardAccessibilityService`-Klassendoc). Dieselbe "kein BFU-Bedarf" Begründung wie
 * `FailsafeStorage` (`CLAUDE.md`, "Direct Boot / BFU-Awareness").
 *
 * Eigener Keystore-Zweck/eigene Dateien statt Wiederverwendung von [ClipboardGuardStorage] oder
 * `SecurityEventStorage`: dieser Bestand trägt tatsächlichen eingefügten Fremdtext — der
 * inhaltlich sensibelste Datenbestand der App neben dem PIN-Blob (dieselbe Einstufung wie
 * `SecurityEventStorage`s Klassendoc für adb-Kommandos/Hostnamen, hier noch direkter: es kann
 * wörtlicher Nutzertext sein).
 */
object ClipboardAccessEventStorage {
    private const val KEYSTORE_PURPOSE = "clipboard-access-events"
    private const val ENVELOPE_CONTEXT = "warden:clipboard-access-events:v1"
    private const val DATA_FILE_NAME = "clipboard_access_events.envelope"
    private const val DEK_FILE_NAME = "clipboard_access_events.dek"

    fun buildEnvelopeFile(context: Context): EnvelopeFile {
        val kek = KeystoreKek.forPurpose(context, KEYSTORE_PURPOSE)
        return EnvelopeFile(
            dataFile = File(context.filesDir, DATA_FILE_NAME),
            wrappedDekFile = File(context.filesDir, DEK_FILE_NAME),
            wrapper = kek,
            context = ENVELOPE_CONTEXT.toByteArray(),
        )
    }
}
