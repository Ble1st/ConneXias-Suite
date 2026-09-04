package de.ble1st.warden.logging

import android.content.Context
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import java.io.File

/**
 * Envelope-Datei-Aufbau für [SecurityEventStore] (2026-08-28) — dasselbe Muster wie
 * [LogStorage]/[de.ble1st.warden.appmanagement.SuspiciousAppScanStorage].
 *
 * Device-Protected Storage: die Batches treffen über `onSecurityLogsAvailable` ein, was auch
 * kurz nach einem Neustart passieren kann, bevor jemand entsperrt hat — auf
 * credential-verschlüsseltem Speicher ginge genau dieser Teil verloren. Verschlüsselt wird
 * trotzdem (anders als bei den reinen Einstellungs-Preferences dieses Projekts): hier stehen
 * Paketnamen, adb-Kommandozeilen und aufgelöste Hostnamen — der inhaltlich sensibelste Datenbestand
 * der App nach dem PIN-Blob.
 */
object SecurityEventStorage {
    private const val KEYSTORE_PURPOSE = "security-events"
    private const val ENVELOPE_CONTEXT = "warden:security-events:v1"
    private const val DATA_FILE_NAME = "security_events.envelope"
    private const val DEK_FILE_NAME = "security_events.dek"

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
