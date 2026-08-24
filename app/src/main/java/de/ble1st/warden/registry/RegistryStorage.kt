package de.ble1st.warden.registry

import android.content.Context
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import java.io.File

/**
 * Meilenstein C.4: baut den kompletten Envelope-Stack für die Registry-Persistenz — bewusst
 * immer über [Context.createDeviceProtectedStorageContext], **nicht** den normalen App-Speicher.
 *
 * **Warum:** Die Boot-Reconciliation (C.4, `RegistryReconciliationReceiver` in `:warden-app`)
 * läuft bei `ACTION_LOCKED_BOOT_COMPLETED` — also **vor** dem Entsperren (Direct Boot/FBE).
 * Wardens normaler, credential-verschlüsselter App-Speicher (`context.filesDir`) ist zu diesem
 * Zeitpunkt noch nicht zugänglich; nur Device-Protected-Storage ist es. `AndroidKeyStore`-
 * Schlüssel ohne `setUnlockedDeviceRequired(true)` (wie [KeystoreKek] sie erzeugt) sind laut
 * Android-FBE-Dokumentation auch vor dem Entsperren nutzbar — kein zusätzlicher Kniff nötig,
 * nur die Dateiablage muss umziehen.
 *
 * **Wichtig für jeden künftigen Aufrufer** (auch aus normalem, bereits entsperrtem Kontext —
 * z. B. eine spätere Bedienoberfläche, Meilenstein G): [buildEnvelopeFile] muss die **einzige**
 * Konstruktionsstelle für die Registry-Envelope-Dateien bleiben. Ein Aufrufer, der stattdessen
 * `context.filesDir` (normalen Speicher) verwendet, sieht eine andere, scheinbar leere
 * Registry — Device-Protected- und Credential-Protected-Storage sind physisch getrennte
 * Verzeichnisse, kein automatischer Sync.
 */
object RegistryStorage {
    private const val KEYSTORE_PURPOSE = "registry"
    private const val ENVELOPE_CONTEXT = "warden:registry:v1"
    private const val DATA_FILE_NAME = "registry.envelope"
    private const val DEK_FILE_NAME = "registry.dek"

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
