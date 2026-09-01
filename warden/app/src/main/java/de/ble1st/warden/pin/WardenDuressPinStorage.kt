package de.ble1st.warden.pin

import android.content.Context
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import java.io.File

/**
 * Duress-PIN (GrapheneOS-Vorbild "Duress PIN", 2026-08-22, auf Nutzerwunsch übernommen) — eine
 * zweite, unabhängige PIN, deren Eingabe [de.ble1st.warden.presence.DuressPinResponder] statt
 * echtem Entsperren auslöst. Bewusst **kein** zweites Feld im bestehenden [WardenPinBlob], sondern
 * eine komplett eigene Envelope-Datei mit eigenem AAD-Kontext, damit das bereits gehärtete,
 * getestete Haupt-Blob-Format ([WardenPinBlobCodec]) unangetastet bleibt — [WardenPinBlob]/
 * [WardenPinBlobCodec]/[WardenPinStore] sind alle generisch genug (kein hartkodierter Bezug auf
 * "die eine" PIN), um für diesen zweiten, unabhängigen Zustand wiederverwendet zu werden, statt sie
 * zu duplizieren.
 *
 * `ENVELOPE_CONTEXT = "warden:duress-pin:v1"` — eigene AAD-Domain-Separation, dieselbe Begründung
 * wie bei [WardenPinStorage]/[WardenLockScreenTextStorage] & Co. Device-Protected-Storage aus
 * demselben Grund wie [WardenPinStorage]: falls ein künftiger Boot-Pfad diesen Blob je lesen muss,
 * funktioniert das auch vor dem Entsperren.
 */
object WardenDuressPinStorage {
    private const val ENVELOPE_CONTEXT = "warden:duress-pin:v1"
    private const val ANCHOR_CONTEXT = "warden:duress-pin-replay:v1"

    fun buildBlobFile(context: Context): EnvelopeFile {
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        val kek = KeystoreKek.forPurpose(deviceProtectedContext, "warden-duress-pin-blob")
        return EnvelopeFile(
            dataFile = File(deviceProtectedContext.filesDir, "warden_duress_pin_blob.envelope"),
            wrappedDekFile = File(deviceProtectedContext.filesDir, "warden_duress_pin_blob.dek"),
            wrapper = kek,
            context = ENVELOPE_CONTEXT.toByteArray(),
        )
    }

    fun buildReplayAnchorFile(context: Context): EnvelopeFile {
        val deviceProtectedContext = context.createDeviceProtectedStorageContext()
        val kek = KeystoreKek.forPurpose(deviceProtectedContext, "warden-duress-pin-replay")
        return EnvelopeFile(
            dataFile = File(deviceProtectedContext.filesDir, "warden_duress_pin_blob.anchor.envelope"),
            wrappedDekFile = File(deviceProtectedContext.filesDir, "warden_duress_pin_blob.anchor.dek"),
            wrapper = kek,
            context = ANCHOR_CONTEXT.toByteArray(),
        )
    }

    fun openStore(context: Context): WardenPinStore =
        WardenPinStore(buildBlobFile(context), buildReplayAnchorFile(context))
}
