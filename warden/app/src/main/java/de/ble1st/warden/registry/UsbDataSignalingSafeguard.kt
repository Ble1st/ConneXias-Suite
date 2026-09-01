package de.ble1st.warden.registry

import android.content.Context

/**
 * Schaltet USB-Data-Signaling geräteweit ab (Meilenstein C.5, Konzept Abschnitt 4:
 * "Geräte-Lockdown-Bündel: USB-Data-Signaling aus, ..."). Eigene DPM-API seit API 31
 * (`setUsbDataSignalingEnabled`/`isUsbDataSignalingEnabled`/`canUsbDataSignalingBeDisabled`) —
 * **ohne** `ComponentName admin`-Parameter, anders als die übrigen `DpmSafeguard`-Ableitungen
 * (Android verlangt hier implizit den Device/Profile-Owner-Aufrufer, keine explizite Admin-
 * Angabe). Erbt trotzdem von [DpmSafeguard] für den gemeinsamen `devicePolicyManager()`-Zugriff,
 * nutzt `admin` selbst aber nicht.
 *
 * `canUsbDataSignalingBeDisabled()` ist eine Laufzeit-Absicherung: nicht jedes Gerät/jeder
 * Kernel unterstützt das Abschalten (siehe AOSP-Doku "Disable data signaling over USB") — [apply]
 * wirft statt still zu tun, als wäre es gelungen, wenn das Gerät es nicht unterstützt (Fail-Safe,
 * Invariante 6: kein stiller Fake-Erfolg).
 *
 * **Bewusst nirgends live per `apply()`/`revert()` getestet** (weder hier noch in einem
 * Instrumented Test): USB-Data-Signaling abzuschalten kappt auf dem per USB angeschlossenen
 * Testgerät mutmaßlich sofort die eigene `adb`-Verbindung, über die der Test überhaupt erst
 * läuft — ein Fehlschlag würde die aktuelle Arbeitssitzung unterbrechen, nicht nur das Gerät.
 */
class UsbDataSignalingSafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        check(devicePolicyManager().canUsbDataSignalingBeDisabled()) {
            "Gerät/Kernel unterstützt das Abschalten von USB-Data-Signaling nicht"
        }
        devicePolicyManager().setUsbDataSignalingEnabled(false)
    }

    override fun revert() {
        devicePolicyManager().setUsbDataSignalingEnabled(true)
    }

    override fun isActive(): Boolean = !devicePolicyManager().isUsbDataSignalingEnabled

    companion object {
        const val ID = "usb_data_signaling_disabled"
    }
}
