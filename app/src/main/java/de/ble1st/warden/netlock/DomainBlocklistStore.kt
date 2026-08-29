package de.ble1st.warden.netlock

import android.content.Context
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * "Netz-Sperre" (2026-08-27): Nutzer-verwaltete Domain-Blockliste plus eine kleine eingebettete
 * Standardliste bekannter Tracking-/Ad-Domains — bewusst als String-Konstante im Code, kein
 * Netz-Download (Warden bleibt offline-first, s. z. B. [de.ble1st.warden.registry.FactoryResetProtectionSafeguard]s
 * ähnliche Zurückhaltung gegenüber Netzabhängigkeiten). Kein Anspruch auf Vollständigkeit — eine
 * kuratierte Auswahl weit verbreiteter Tracker/Ad-Netzwerke, kein Ersatz für eine echte,
 * community-gepflegte Blockliste; der Nutzer kann jederzeit eigene Domains ergänzen.
 */
class DomainBlocklistStore(private val envelopeFile: EnvelopeFile) {

    fun loadUserDomains(): Set<String> =
        if (envelopeFile.hasStorage()) decodeDomains(envelopeFile.read()) else emptySet()

    fun addDomain(domain: String) {
        val normalized = domain.trim().trim('.').lowercase()
        if (normalized.isEmpty()) return
        val updated = loadUserDomains() + normalized
        envelopeFile.write(encodeDomains(updated))
    }

    fun removeDomain(domain: String) {
        val normalized = domain.trim().trim('.').lowercase()
        val updated = loadUserDomains() - normalized
        envelopeFile.write(encodeDomains(updated))
    }

    /** Nutzerliste + eingebettete Standardliste zusammen — direkte Eingabe für
     * `BarbicanEngine.setBlocklist`. */
    fun effectiveBlocklist(): Set<String> = loadUserDomains() + DEFAULT_TRACKER_DOMAINS

    companion object {
        private const val KEYSTORE_PURPOSE = "net_blocklist"
        private const val ENVELOPE_CONTEXT = "warden:net_blocklist:v1"
        private const val DATA_FILE_NAME = "net_blocklist.envelope"
        private const val DEK_FILE_NAME = "net_blocklist.dek"

        /** Kuratierte Auswahl weit verbreiteter Tracking-/Ad-Domains — bewusst klein gehalten,
         * kein Anspruch auf Vollständigkeit (s. Klassendoc). */
        val DEFAULT_TRACKER_DOMAINS: Set<String> = setOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "google-analytics.com",
            "app-measurement.com",
            "graph.facebook.com",
            "connect.facebook.net",
            "ads.facebook.com",
            "adjust.com",
            "appsflyer.com",
            "branch.io",
            "amplitude.com",
            "mixpanel.com",
            "scorecardresearch.com",
            "adservice.google.com",
        )

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

internal fun encodeDomains(domains: Set<String>): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use { data ->
        data.writeInt(domains.size)
        for (domain in domains) {
            data.writeUTF(domain)
        }
    }
    return out.toByteArray()
}

internal fun decodeDomains(bytes: ByteArray): Set<String> {
    val data = DataInputStream(bytes.inputStream())
    val count = data.readInt()
    return buildSet {
        repeat(count) { add(data.readUTF()) }
    }
}
