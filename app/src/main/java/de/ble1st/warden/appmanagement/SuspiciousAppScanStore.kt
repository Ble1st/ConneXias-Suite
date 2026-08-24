package de.ble1st.warden.appmanagement

import de.ble1st.warden.crypto.EnvelopeFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Milestone "Automatisches Einfrieren verdächtiger Apps" (2026-08-20) — envelope-persistiert
 * (dieselbe Device-Protected-Storage-Einstufung wie `FirewallPolicyStorage`/`NetLockdownStore`)
 * zwei Felder: ob der Scanner überhaupt aktiv ist (Default **aus** — kein automatisches
 * Einfrieren ohne explizites Opt-in, s. `SuspiciousAppScanController`-Klassendoc) und welche
 * Pakete der Nutzer trotz eines Verdachtssignals bewusst als vertrauenswürdig markiert hat
 * (verhindert ein Einfrieren-Vertrauen-Wieder-Einfrieren-Pingpong bei jedem Scan-Lauf).
 */
class SuspiciousAppScanStore(private val envelopeFile: EnvelopeFile) {

    fun isEnabled(): Boolean = load().enabled

    fun trustedPackages(): Set<String> = load().trusted

    fun setEnabled(enabled: Boolean) {
        envelopeFile.write(encode(enabled, trustedPackages()))
    }

    fun trust(packageName: String) {
        envelopeFile.write(encode(isEnabled(), trustedPackages() + packageName))
    }

    private fun load(): ScanState =
        if (envelopeFile.hasStorage()) decode(envelopeFile.read()) else ScanState(enabled = false, trusted = emptySet())
}

private data class ScanState(val enabled: Boolean, val trusted: Set<String>)

/** Format: `[1 byte enabled][4 byte count][each: UTF package name]`. */
private fun encode(enabled: Boolean, trusted: Set<String>): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use { data ->
        data.writeBoolean(enabled)
        data.writeInt(trusted.size)
        for (pkg in trusted.sorted()) {
            data.writeUTF(pkg)
        }
    }
    return out.toByteArray()
}

private fun decode(bytes: ByteArray): ScanState {
    val data = DataInputStream(bytes.inputStream())
    val enabled = data.readBoolean()
    val count = data.readInt()
    val trusted = buildSet {
        repeat(count) {
            add(data.readUTF())
        }
    }
    return ScanState(enabled, trusted)
}
