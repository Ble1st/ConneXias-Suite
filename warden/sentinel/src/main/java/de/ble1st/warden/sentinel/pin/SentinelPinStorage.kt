package de.ble1st.warden.sentinel.pin

import android.content.Context
import de.ble1st.warden.sentinel.crypto.EnvelopeFile
import de.ble1st.warden.sentinel.crypto.KeystoreKek
import java.io.File

/**
 * Baut die Envelope-Datei für Sentinels PIN-Blob — analog Wardens `WardenPinStorage`, aber
 * bewusst **kein** `createDeviceProtectedStorageContext()`: Sentinel hat keinen Boot-Zeit-Pfad
 * (kein Direct-Boot-Receiver, kein Registry-Reconciliation-Äquivalent), die PIN wird
 * ausschließlich innerhalb eines laufenden Kiosk-Screens gebraucht, der ohnehin erst nach dem
 * Entsperren erreichbar ist (Warden selbst startet Sentinel nie vor dem ersten Entsperren, s.
 * `de.ble1st.warden.presence.SentinelLockdownEngager`). Normaler, credential-verschlüsselter
 * App-Speicher genügt.
 *
 * `ENVELOPE_CONTEXT = "sentinel:pin:v1"` — eigene AAD-Domain-Separation, unabhängig von Wardens
 * `"warden:pin:v1"`.
 */
object SentinelPinStorage {
    private const val ENVELOPE_CONTEXT = "sentinel:pin:v1"

    fun buildBlobFile(context: Context): EnvelopeFile {
        val kek = KeystoreKek.forPurpose(context, "sentinel-pin-blob")
        return EnvelopeFile(
            dataFile = File(context.filesDir, "sentinel_pin_blob.envelope"),
            wrappedDekFile = File(context.filesDir, "sentinel_pin_blob.dek"),
            wrapper = kek,
            context = ENVELOPE_CONTEXT.toByteArray(),
        )
    }

    fun openStore(context: Context): SentinelPinStore = SentinelPinStore(buildBlobFile(context))
}
