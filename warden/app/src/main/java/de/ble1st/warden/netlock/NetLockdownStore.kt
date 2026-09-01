package de.ble1st.warden.netlock

import android.content.Context
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import java.io.File

/**
 * "Netz-Sperre" (2026-08-27): persistiert den *Soll*-Wert "Net-Lockdown scharf?" —
 * **Device-Protected Storage**, identische Begründung wie [de.ble1st.warden.registry.RegistryStorage]:
 * [de.ble1st.warden.boot.RegistryReconciliationReceiver] läuft bei `ACTION_LOCKED_BOOT_COMPLETED`,
 * also vor dem Entsperren — nur Device-Protected-Storage ist zu diesem Zeitpunkt lesbar.
 *
 * Ein einzelnes Boolean über [EnvelopeFile] statt Klartext-`SharedPreferences` (anders als z. B.
 * [de.ble1st.warden.registry.WardenOrganizationNameStorage]): der Soll-Wert "Netz-Sperre gewollt"
 * ist sicherheitsrelevanter Zustand (bestimmt, ob nach Reboot ein Always-On-VPN-Lockdown erneut
 * durchgesetzt wird) — ein manipulierter/verlorener Klartext-Wert wäre ein stiller Fail-Open, kein
 * bloßer UI-Komfortverlust.
 */
class NetLockdownStore(private val envelopeFile: EnvelopeFile) {

    /** `null`, wenn noch nie ein Soll-Wert gespeichert wurde (weder scharf noch entschärft je
     * bewusst gesetzt) — Aufrufer müssen das von "explizit entschärft" (`false`) unterscheiden. */
    fun loadDesiredArmed(): Boolean? =
        if (envelopeFile.hasStorage()) envelopeFile.read()[0] != 0.toByte() else null

    fun saveDesiredArmed(armed: Boolean) {
        envelopeFile.write(byteArrayOf(if (armed) 1 else 0))
    }

    companion object {
        private const val KEYSTORE_PURPOSE = "net_lockdown"
        private const val ENVELOPE_CONTEXT = "warden:net_lockdown:v1"
        private const val DATA_FILE_NAME = "net_lockdown.envelope"
        private const val DEK_FILE_NAME = "net_lockdown.dek"

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
}
