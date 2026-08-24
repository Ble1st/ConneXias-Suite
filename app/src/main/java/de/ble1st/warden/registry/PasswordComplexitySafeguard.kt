package de.ble1st.warden.registry

import android.app.admin.DevicePolicyManager
import android.content.Context

/**
 * Eigene Idee, ergänzend zu den Tier-1-6-Punkten (2026-08-22) — "Passwort-/Sperrbildschirm-Policy"
 * war in der ursprünglichen Liste als Tier 4 vorgesehen, aber noch nicht umgesetzt: erzwingt eine
 * Mindest-Komplexität für den echten OS-Sperrbildschirm-PIN/-Passwort über
 * `DevicePolicyManager.setRequiredPasswordComplexity`/`getRequiredPasswordComplexity` —
 * ergänzt Wardens eigenen, separaten lokalen PIN ([de.ble1st.warden.pin]), der nur den
 * presence-gated Aktionspfad schützt, nicht den eigentlichen Gerätesperrbildschirm.
 *
 * **Ohne `ComponentName admin`-Parameter**, wie [UsbDataSignalingSafeguard] (dortiges Klassendoc)
 * — Android verlangt hier implizit den Device-/Profile-Owner-Aufrufer.
 *
 * `PASSWORD_COMPLEXITY_HIGH` verlangt je nach Android-Version mindestens eine 8-stellige PIN ohne
 * wiederkehrendes/aufsteigendes Muster oder ein alphanumerisches Passwort — die Nutzerin muss die
 * Systemsperre ggf. selbst neu setzen, falls die aktuelle sie nicht erfüllt (Android fordert das
 * dann über einen eigenen Systemdialog ein, kein Warden-eigener Flow nötig).
 */
class PasswordComplexitySafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        devicePolicyManager().requiredPasswordComplexity = DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH
    }

    override fun revert() {
        devicePolicyManager().requiredPasswordComplexity = DevicePolicyManager.PASSWORD_COMPLEXITY_NONE
    }

    override fun isActive(): Boolean =
        devicePolicyManager().requiredPasswordComplexity >= DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH

    companion object {
        const val ID = "password_complexity_high"
    }
}
