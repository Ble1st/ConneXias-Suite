package de.ble1st.warden.appmanagement

import android.content.Context
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import java.io.File

/** Envelope-Datei-Aufbau für [SuspiciousAppScanStore] — dasselbe Muster wie
 * [de.ble1st.warden.registry.RegistryStorage]. */
object SuspiciousAppScanStorage {
    private const val KEYSTORE_PURPOSE = "suspicious-app-scan"
    private const val ENVELOPE_CONTEXT = "warden:suspicious-app-scan:v1"
    private const val DATA_FILE_NAME = "suspicious_app_scan.envelope"
    private const val DEK_FILE_NAME = "suspicious_app_scan.dek"

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
