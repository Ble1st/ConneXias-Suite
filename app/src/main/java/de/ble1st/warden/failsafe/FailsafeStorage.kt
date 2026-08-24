package de.ble1st.warden.failsafe

import android.content.Context
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import java.io.File

/**
 * Meilenstein D.1/D.3: baut die drei Envelope-Dateien des Offline-Failsafes — analog zu
 * `RegistryStorage`/`LogStorage` (Meilenstein C.4), aber **bewusst über den normalen,
 * credential-verschlüsselten App-Speicher** (`context.filesDir`), **nicht**
 * [Context.createDeviceProtectedStorageContext] wie dort.
 *
 * **Warum der Unterschied zu Registry/Log:** Der Offline-Failsafe ist ein interaktiver Vorgang
 * (Konzept Abschnitt 9: "Challenge auf dem Gerät erzeugt/angezeigt, Response ... lokal
 * eingegeben") über eine Warden-eigene UI-Ansicht (Meilenstein D.3, `FailsafeActivity`,
 * `:warden-app`) — anders als die Registry-Reconciliation (C.4) gibt es hier keinen
 * `ACTION_LOCKED_BOOT_COMPLETED`-Auslöser, der vor dem Entsperren laufen müsste. Solange keine
 * eigene "Failsafe vor dem Entsperren"-Anforderung entsteht, bleibt normaler Speicher die
 * einfachere, korrekte Wahl — device-protected Speicher vorsorglich zu verwenden würde nur eine
 * zweite, potenziell disjunkte Ablage riskieren (dieselbe Falle, vor der die Registry/Log-Doku
 * warnt), ohne aktuell einen Vorteil zu bieten.
 *
 * Drei getrennte Envelope-Dateien mit eigenem Zweck/Kontext (Konzept 10: "eigene Aliase pro
 * Zweck") statt einer gemeinsamen: Vertrauensschlüssel, ausstehende Challenge und
 * Credential-Reset-Token haben unterschiedliche Lebenszyklen (der Schlüssel wird einmalig
 * konfiguriert und bleibt bestehen, die Challenge kommt und geht pro Durchlauf, das Token wird
 * einmalig erzeugt und danach nur gelesen) — eine gemeinsame Datei würde diese Unabhängigkeit
 * unnötig koppeln.
 */
object FailsafeStorage {
    private const val ENVELOPE_CONTEXT_PREFIX = "warden:failsafe:"

    fun buildTrustedKeyFile(context: Context): EnvelopeFile =
        build(context, purpose = "recovery-key", dataFileName = "failsafe_key.envelope", dekFileName = "failsafe_key.dek")

    fun buildChallengeFile(context: Context): EnvelopeFile =
        build(context, purpose = "challenge", dataFileName = "failsafe_challenge.envelope", dekFileName = "failsafe_challenge.dek")

    fun buildResetTokenFile(context: Context): EnvelopeFile =
        build(context, purpose = "reset-token", dataFileName = "failsafe_reset_token.envelope", dekFileName = "failsafe_reset_token.dek")

    private fun build(context: Context, purpose: String, dataFileName: String, dekFileName: String): EnvelopeFile {
        val kek = KeystoreKek.forPurpose(context, "failsafe-$purpose")
        return EnvelopeFile(
            dataFile = File(context.filesDir, dataFileName),
            wrappedDekFile = File(context.filesDir, dekFileName),
            wrapper = kek,
            context = (ENVELOPE_CONTEXT_PREFIX + purpose + ":v1").toByteArray(),
        )
    }
}
