package de.ble1st.warden.registry

import android.content.Context

/**
 * Eigene Idee, ergänzend zu den Tier-1-6-Punkten (2026-08-22) — schaltet Androids Backup-Dienst
 * geräteweit ab (`DevicePolicyManager.setBackupServiceEnabled`/`isBackupServiceEnabled`):
 * verhindert, dass App-Daten über Cloud-Backup oder `adb backup` das Gerät verlassen.
 * Manifest `allowBackup` is already `false`; this remains optional defense-in-depth. Passt zur
 * Anti-Tamper-Linie aus Tier 1 — dort ging es um Deinstallations-/Stopp-Schutz, hier um den
 * Datenexfiltrations-Weg über den Systemdienst selbst.
 *
 * Reversibel über dieselbe API, kein Rückbau-Risiko — regulär über
 * [de.ble1st.warden.ui.SafeguardsScreen] umschaltbar.
 */
class BackupServiceLockdownSafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        devicePolicyManager().setBackupServiceEnabled(admin, false)
    }

    override fun revert() {
        devicePolicyManager().setBackupServiceEnabled(admin, true)
    }

    override fun isActive(): Boolean = !devicePolicyManager().isBackupServiceEnabled(admin)

    companion object {
        const val ID = "backup_service_disabled"
    }
}
