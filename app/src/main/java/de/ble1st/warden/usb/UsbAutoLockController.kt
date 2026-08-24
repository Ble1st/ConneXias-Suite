package de.ble1st.warden.usb

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import de.ble1st.warden.domain.usb.UsbAutoLockDecision
import de.ble1st.warden.registry.DeviceLockdownBundle
import de.ble1st.warden.registry.RegistryStorage
import de.ble1st.warden.registry.SafeguardRegistryStore
import de.ble1st.warden.registry.UsbDataSignalingSafeguard
import de.ble1st.warden.wardenAuditLog

/**
 * GrapheneOS-Vorbild "USB-C-Anschluss: nur Laden, wenn gesperrt" (2026-08-22, auf Nutzerwunsch
 * übernommen). Koppelt [de.ble1st.warden.registry.UsbDataSignalingSafeguard]s zugrunde liegende
 * DPM-API (`setUsbDataSignalingEnabled`) an den Sperrzustand, statt sie als statischen, immer-aus
 * Schalter zu betreiben: gesperrt → USB-Datenverkehr aus, entsperrt → wieder an. Aufgerufen von
 * [UsbAutoLockWorker], ergänzt um einen dynamisch registrierten
 * [UsbLockStateReceiver] (`SCREEN_OFF` / `USER_PRESENT` / `SCREEN_ON`) für den Lock-Rand —
 * WorkManager bleibt Backup, falls der Prozess am Lock-Zeitpunkt nicht lebt.
 *
 * Unlock never re-enables USB while permanent USB-off or [DeviceLockdownBundle] is the
 * persisted desired state ([UsbAutoLockDecision]). Auto-lock itself stays as-is when those
 * are off.
 *
 * **Wichtige Einschränkung, anders als bei Auto-Reboot:** GrapheneOS' echtes Vorbild reagiert
 * kernelseitig sofort auf Sperren/Entsperren; ohne lebenden Prozess gilt weiter das
 * WorkManager-Poll-Intervall (bis zu ~15 Minuten) als Backup.
 *
 * **Bewusst standardmäßig aus** ([UsbAutoLockStorage], Default `false`) und **nirgends live am
 * angeschlossenen Testgerät getestet** — s. [de.ble1st.warden.registry.UsbDataSignalingSafeguard]
 * -Klassendoc: das Abschalten von USB-Data-Signaling kappt mutmaßlich sofort die eigene
 * `adb`-Verbindung, über die dieses Projekt entwickelt wird.
 */
class UsbAutoLockController(private val context: Context) {

    fun checkAndSync(lockedOverride: Boolean? = null) {
        if (!UsbAutoLockStorage.isEnabled(context)) return
        val keyguardManager = context.getSystemService(KeyguardManager::class.java) ?: return
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val isLocked = lockedOverride ?: keyguardManager.isKeyguardLocked
        val logStore = wardenAuditLog(context)
        val desired = runCatching {
            SafeguardRegistryStore(RegistryStorage.buildEnvelopeFile(context)).load()
        }
        val action = UsbAutoLockDecision.action(
            isLocked = isLocked,
            registryLoadFailed = desired.isFailure,
            permanentUsbOffDesired = desired.getOrNull()?.get(UsbDataSignalingSafeguard.ID) == true,
            lockdownDesired = desired.getOrNull()?.get(DeviceLockdownBundle.ID) == true,
        )

        try {
            when (action) {
                UsbAutoLockDecision.Action.Disable -> {
                    if (dpm.isUsbDataSignalingEnabled && dpm.canUsbDataSignalingBeDisabled()) {
                        dpm.setUsbDataSignalingEnabled(false)
                        logStore.append(Log.INFO, TAG, "USB-Data-Signaling deaktiviert")
                    }
                }
                UsbAutoLockDecision.Action.Enable -> {
                    if (!dpm.isUsbDataSignalingEnabled) {
                        dpm.setUsbDataSignalingEnabled(true)
                        logStore.append(Log.INFO, TAG, "USB-Data-Signaling reaktiviert (Gerät entsperrt)")
                    }
                }
                UsbAutoLockDecision.Action.LeaveAsIs -> {
                    logStore.append(Log.WARN, TAG, "USB-Auto-Lock: Registry unlesbar — USB nicht reaktiviert")
                }
            }
        } catch (e: Exception) {
            logStore.append(Log.ERROR, TAG, "USB-Auto-Lock-Abgleich fehlgeschlagen: $e")
        }
    }

    private companion object {
        const val TAG = "UsbAutoLock"
    }
}
