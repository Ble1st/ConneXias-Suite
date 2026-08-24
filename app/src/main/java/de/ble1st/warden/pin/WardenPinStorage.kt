package de.ble1st.warden.pin

import android.content.Context
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import java.io.File

/**
 * Meilenstein H.4 (Konzept Abschnitt 6/19: "Signierter Zustands-Blob in Device-Protected-Storage").
 * Baut die Envelope-Datei für [WardenPinStore] — bewusst über
 * [Context.createDeviceProtectedStorageContext], dieselbe Begründung wie `RegistryStorage`/
 * `LogStorage`: falls ein künftiger Boot-Pfad den PIN-Blob liest, muss das auch **vor** dem
 * Entsperren (Direct Boot/FBE) funktionieren, wenn Wardens normaler, credential-verschlüsselter
 * App-Speicher noch nicht zugänglich ist. `AndroidKeyStore`-Schlüssel ohne
 * `setUnlockedDeviceRequired(true)` (wie `KeystoreKek` sie erzeugt) sind auch vor dem Entsperren
 * nutzbar — dieselbe bereits an anderer Stelle empirisch/dokumentarisch abgesicherte Erkenntnis.
 *
 * `ENVELOPE_CONTEXT = "warden:pin:v1"` statt des Quellprojekt-Werts `"sentinel:blob:v1"` — eigene
 * AAD-Domain-Separation für Wardens eigenen, jetzt lokalen PIN-Blob.
 */
object WardenPinStorage {
    private const val ENVELOPE_CONTEXT = "warden:pin:v1"
    private const val ANCHOR_CONTEXT = "warden:pin-replay:v1"

    fun buildBlobFile(context: Context): EnvelopeFile {
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        val kek = KeystoreKek.forPurpose(deviceProtectedContext, "warden-pin-blob")
        return EnvelopeFile(
            dataFile = File(deviceProtectedContext.filesDir, "warden_pin_blob.envelope"),
            wrappedDekFile = File(deviceProtectedContext.filesDir, "warden_pin_blob.dek"),
            wrapper = kek,
            context = ENVELOPE_CONTEXT.toByteArray(),
        )
    }

    /** Second slot for [de.ble1st.warden.domain.pin.WardenPinReplayDecision] — own KEK and AAD. */
    fun buildReplayAnchorFile(context: Context): EnvelopeFile {
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        val kek = KeystoreKek.forPurpose(deviceProtectedContext, "warden-pin-replay")
        return EnvelopeFile(
            dataFile = File(deviceProtectedContext.filesDir, "warden_pin_blob.anchor.envelope"),
            wrappedDekFile = File(deviceProtectedContext.filesDir, "warden_pin_blob.anchor.dek"),
            wrapper = kek,
            context = ANCHOR_CONTEXT.toByteArray(),
        )
    }

    fun openStore(context: Context): WardenPinStore =
        WardenPinStore(buildBlobFile(context), buildReplayAnchorFile(context))
}
