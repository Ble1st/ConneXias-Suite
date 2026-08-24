package de.ble1st.warden.registry

import android.content.Context

/**
 * Sperrt die Kamera geräteweit (Meilenstein C.2, Konzept Abschnitt 4: "Schalter: ..., Kamera,
 * ..."). Eine eigene DPM-API (`setCameraDisabled`/`getCameraDisabled`), kein
 * `UserManager.DISALLOW_*`-Wert — deshalb eine eigene Klasse statt einer
 * [UserRestrictionSafeguard]-Instanz.
 */
class CameraSafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        devicePolicyManager().setCameraDisabled(admin, true)
    }

    override fun revert() {
        devicePolicyManager().setCameraDisabled(admin, false)
    }

    override fun isActive(): Boolean = devicePolicyManager().getCameraDisabled(admin)

    companion object {
        const val ID = "camera_disabled"
    }
}
