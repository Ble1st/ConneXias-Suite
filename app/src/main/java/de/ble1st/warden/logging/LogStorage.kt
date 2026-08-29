package de.ble1st.warden.logging

import android.content.Context
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import java.io.File

/**
 * Meilenstein C.4: baut den Envelope-Stack für [HashChainLogStore] — bewusst immer über
 * [Context.createDeviceProtectedStorageContext], aus demselben Grund wie
 * `RegistryStorage` (`:core:data`, siehe dortige Klassendoc): die Boot-Reconciliation
 * (`RegistryReconciliationReceiver` in `:warden-app`) protokolliert bei
 * `ACTION_LOCKED_BOOT_COMPLETED` — **vor** dem Entsperren (Direct Boot/FBE) —, wenn Wardens
 * normaler, credential-verschlüsselter App-Speicher noch nicht zugänglich ist.
 *
 * **Wichtig:** Der zentrale Log-Store (Konzept Abschnitt 4/12: "Zentraler append-only Log-Store
 * in Warden") ist als *eine durchgehende Kette* gedacht — jeder künftige Aufrufer (auch aus
 * normalem, entsperrtem Kontext) muss [buildEnvelopeFile] verwenden, sonst entstehen zwei
 * disjunkte Ketten in unterschiedlichen Speicherorten statt einer einzigen Wahrheit.
 */
object LogStorage {
    private const val KEYSTORE_PURPOSE = "log"
    private const val ENVELOPE_CONTEXT = "warden:log:v1"
    private const val DATA_FILE_NAME = "log.envelope"
    private const val DEK_FILE_NAME = "log.dek"

    private const val ANCHOR_KEYSTORE_PURPOSE = "log-wipe-guard"
    private const val ANCHOR_CONTEXT = "warden:log-wipe-guard:v1"
    private const val ANCHOR_DATA_FILE_NAME = "log.wipeguard.envelope"
    private const val ANCHOR_DEK_FILE_NAME = "log.wipeguard.dek"

    private const val RETENTION_KEYSTORE_PURPOSE = "log-retention"
    private const val RETENTION_CONTEXT = "warden:log-retention:v1"
    private const val RETENTION_DATA_FILE_NAME = "log.retention.envelope"
    private const val RETENTION_DEK_FILE_NAME = "log.retention.dek"

    /**
     * Aufbewahrungsgrenze fürs Audit-Log (2026-08-28, Befund Q-3): 20 archivierte Segmente à
     * [HashChainLogStore.DEFAULT_SEGMENT_CAPACITY] Einträge, dazu das aktive Segment — also rund
     * 10 000 Einträge Historie, die [HashChainLogStore.entries] bei jeder Log-Einsicht liest und
     * entschlüsselt.
     *
     * Die Zahl ist eine Abwägung, keine technische Grenze: das Audit-Log hält Wardens eigene
     * Entscheidungen fest (schaltende Kommandos, Presence-Nachweise, abgelehnte Aufrufe), und seit
     * derselben Analyse fallen die reinen Lesezugriffe weg (s. `ConcordBus.log`) — das ist der
     * Grund, warum 10 000 Einträge nach der Umstellung sehr viel weiter reichen als vorher.
     */
    const val KEEP_ARCHIVED_LOG_SEGMENTS = 20

    fun buildEnvelopeFile(context: Context): EnvelopeFile {
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        val kek = KeystoreKek.forPurpose(deviceProtectedContext, KEYSTORE_PURPOSE)
        return EnvelopeFile(
            dataFile = File(deviceProtectedContext.filesDir, DATA_FILE_NAME),
            wrappedDekFile = File(deviceProtectedContext.filesDir, DEK_FILE_NAME),
            wrapper = kek,
            context = ENVELOPE_CONTEXT.toByteArray(),
        )
    }

    /** Second slot for [de.ble1st.warden.domain.logging.HashChainWipeGuardDecision] — own KEK
     * and AAD, same reasoning as [de.ble1st.warden.pin.WardenPinStorage.buildReplayAnchorFile]. */
    fun buildWipeGuardAnchorFile(context: Context): EnvelopeFile {
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        val kek = KeystoreKek.forPurpose(deviceProtectedContext, ANCHOR_KEYSTORE_PURPOSE)
        return EnvelopeFile(
            dataFile = File(deviceProtectedContext.filesDir, ANCHOR_DATA_FILE_NAME),
            wrappedDekFile = File(deviceProtectedContext.filesDir, ANCHOR_DEK_FILE_NAME),
            wrapper = kek,
            context = ANCHOR_CONTEXT.toByteArray(),
        )
    }

    /**
     * Dritter Slot, für [de.ble1st.warden.domain.logging.HashChainRetentionDecision] — eigener
     * KEK und eigene AAD, aus demselben Grund wie beim Wipe-Guard-Anker daneben. Bewusst **nicht**
     * dieselbe Datei wie der Wipe-Guard: die beiden Anker beantworten gegenläufige Fragen ("ist
     * hinten etwas verschwunden?" gegen "wurde vorn absichtlich verworfen?"), und ein gemeinsamer
     * Slot hieße, dass ein Schreibvorgang der einen Prüfung die andere mit anfasst.
     */
    fun buildRetentionAnchorFile(context: Context): EnvelopeFile {
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        val kek = KeystoreKek.forPurpose(deviceProtectedContext, RETENTION_KEYSTORE_PURPOSE)
        return EnvelopeFile(
            dataFile = File(deviceProtectedContext.filesDir, RETENTION_DATA_FILE_NAME),
            wrappedDekFile = File(deviceProtectedContext.filesDir, RETENTION_DEK_FILE_NAME),
            wrapper = kek,
            context = RETENTION_CONTEXT.toByteArray(),
        )
    }

    /** Der eine, überall identisch konfigurierte Audit-Log-Store — s. Klassendoc: zwei
     * unterschiedlich gebaute Instanzen wären zwei Wahrheiten über dieselbe Kette. */
    fun buildAuditLogStore(context: Context): HashChainLogStore = HashChainLogStore(
        envelopeFile = buildEnvelopeFile(context),
        wipeGuardAnchorFile = buildWipeGuardAnchorFile(context),
        retentionAnchorFile = buildRetentionAnchorFile(context),
        keepArchivedSegments = KEEP_ARCHIVED_LOG_SEGMENTS,
    )
}
