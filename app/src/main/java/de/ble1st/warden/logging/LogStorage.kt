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
}
