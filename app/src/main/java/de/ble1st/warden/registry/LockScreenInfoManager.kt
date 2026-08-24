package de.ble1st.warden.registry

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import de.ble1st.warden.admin.WardenDeviceAdminReceiver

/**
 * Setzt den frei wählbaren Zusatztext auf dem **echten OS-Sperrbildschirm** (Keyguard, nicht
 * Wardens eigenem Warden-PIN-Screen!) über die dedizierte Device-Owner-API
 * `DevicePolicyManager.setDeviceOwnerLockScreenInfo` (API 24+) — dieselbe Mechanik, mit der z. B.
 * "Dieses Gerät wird von deiner Organisation verwaltet: <Text>" auf verwalteten Geräten angezeigt
 * wird. **Kein eigenes UI-Rendering nötig:** der Keyguard selbst zeigt den Text an, unabhängig
 * davon, ob Wardens eigener Prozess gerade läuft — `system_server` rendert ihn.
 *
 * Kein [de.ble1st.warden.domain.registry.Safeguard] — bewusst kein Boolean-"an/aus", sondern ein
 * Freitext-Wert, deshalb eigene, kleine Klasse statt einer weiteren [DpmSafeguard]-Unterklasse
 * (dieselbe `admin`/`DevicePolicyManager`-Verkabelung, aber dupliziert statt geerbt — die
 * [de.ble1st.warden.domain.registry.Safeguard]-Basis passt für einen Wert-Typ nicht). Trotzdem
 * bewusst denselben Soll-vs-Ist-Reconciliation-Gedanken wie die übrigen Safeguards: der Soll-Wert
 * lebt in [de.ble1st.warden.pin.WardenLockScreenTextStorage] (Device-Protected-Storage),
 * [de.ble1st.warden.boot.RegistryReconciliationReceiver] appliziert ihn bei Boot erneut, falls die
 * DPM-Policy zwischenzeitlich gedriftet ist.
 *
 * `apply(null)`/`apply("")` löscht den Text wieder.
 */
class LockScreenInfoManager(context: Context) {

    private val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)
    private val dpm = checkNotNull(context.getSystemService(DevicePolicyManager::class.java)) {
        "DevicePolicyManager nicht verfügbar"
    }

    fun apply(text: String?) {
        dpm.setDeviceOwnerLockScreenInfo(admin, text ?: "")
    }

    /** Immer live von der DPM abgefragt, nie gecacht — dieselbe Fail-Safe-Haltung wie
     * `Safeguard.isActive()`: der tatsächliche OS-Zustand zählt, nicht ein nur angenommener. */
    fun current(): String? = dpm.deviceOwnerLockScreenInfo?.toString()?.takeIf { it.isNotEmpty() }
}
