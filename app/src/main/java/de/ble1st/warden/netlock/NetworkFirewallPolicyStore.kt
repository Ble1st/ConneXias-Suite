package de.ble1st.warden.netlock

import android.content.Context
import de.ble1st.warden.crypto.EnvelopeFile
import de.ble1st.warden.crypto.KeystoreKek
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * "Netz-Sperre" (2026-08-27): pro-App-Firewall-Einordnung — zwei Tiers, nicht drei (s.
 * `NetLockdownController`/Plan "Architektur-Vereinfachung"): [FirewallMode.ALLOWED] geht am
 * Tunnel komplett vorbei (`VpnService.Builder.addDisallowedApplication`, ungefiltertes Internet),
 * [FirewallMode.CAPTURED] (Default für jede nicht gelistete App) läuft durch den Tunnel und wird
 * dort DNS-blocklisten-gefiltert. Anders als [de.ble1st.warden.registry.SafeguardRegistryStore]s
 * `id -> Boolean` speichert diese Store eine `packageName -> FirewallMode`-Map — dasselbe
 * längenpräfigierte Storage-Format, nur mit einem zusätzlichen Enum-Ordinal-Byte pro Eintrag.
 */
class NetworkFirewallPolicyStore(private val envelopeFile: EnvelopeFile) {

    /** Leere Map, wenn noch nie gespeichert wurde — bedeutet "jede App ist CAPTURED" (Default),
     * nicht "keine Policy vorhanden" (Fail-Safe: eine kaputte Datei muss lesbar-oder-Exception
     * sein, nie still leer werden, s. [EnvelopeFile]-Klassendoc — deshalb `hasStorage()`-Prüfung
     * statt eines pauschalen `runCatching`). */
    fun load(): Map<String, FirewallMode> =
        if (envelopeFile.hasStorage()) decodePolicy(envelopeFile.read()) else emptyMap()

    fun save(policy: Map<String, FirewallMode>) {
        envelopeFile.write(encodePolicy(policy))
    }

    fun modeFor(packageName: String): FirewallMode = load()[packageName] ?: FirewallMode.CAPTURED

    fun setMode(packageName: String, mode: FirewallMode) {
        val current = load().toMutableMap()
        if (mode == FirewallMode.CAPTURED) {
            // CAPTURED ist der Default — ein expliziter Eintrag dafür wäre nur totes Gewicht,
            // das bei jedem Reload/Diff unnötig mitgeschleppt würde.
            current.remove(packageName)
        } else {
            current[packageName] = mode
        }
        save(current)
    }

    /** Paketnamen, die aktuell am Tunnel vorbeigehen sollen — direkte Eingabe für
     * `VpnService.Builder.addDisallowedApplication` und die DPM-Lockdown-Allowlist gleichermaßen
     * (beide müssen synchron gehalten werden, s. `NetLockdownAuthorizer`-Klassendoc). */
    fun allowedPackageNames(): Set<String> =
        load().filterValues { it == FirewallMode.ALLOWED }.keys

    companion object {
        private const val KEYSTORE_PURPOSE = "net_firewall_policy"
        private const val ENVELOPE_CONTEXT = "warden:net_firewall_policy:v1"
        private const val DATA_FILE_NAME = "net_firewall_policy.envelope"
        private const val DEK_FILE_NAME = "net_firewall_policy.dek"

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

enum class FirewallMode { ALLOWED, CAPTURED }

internal fun encodePolicy(policy: Map<String, FirewallMode>): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use { data ->
        data.writeInt(policy.size)
        for ((packageName, mode) in policy) {
            data.writeUTF(packageName)
            data.writeByte(mode.ordinal)
        }
    }
    return out.toByteArray()
}

internal fun decodePolicy(bytes: ByteArray): Map<String, FirewallMode> {
    val data = DataInputStream(bytes.inputStream())
    val count = data.readInt()
    val modes = FirewallMode.entries
    return buildMap {
        repeat(count) {
            val packageName = data.readUTF()
            val ordinal = data.readByte().toInt()
            put(packageName, modes.getOrElse(ordinal) { FirewallMode.CAPTURED })
        }
    }
}
