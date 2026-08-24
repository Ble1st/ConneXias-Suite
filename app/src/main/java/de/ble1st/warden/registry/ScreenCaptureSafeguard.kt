package de.ble1st.warden.registry

import android.content.Context

/**
 * Sperrt Screenshots/Bildschirmaufnahmen geräteweit (Meilenstein C.2, Konzept Abschnitt 4:
 * "Schalter: ..., Screen-Capture, ..."). Eine eigene DPM-API
 * (`setScreenCaptureDisabled`/`getScreenCaptureDisabled`), kein `UserManager.DISALLOW_*`-Wert —
 * deshalb eine eigene Klasse statt einer [UserRestrictionSafeguard]-Instanz.
 */
class ScreenCaptureSafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        devicePolicyManager().setScreenCaptureDisabled(admin, true)
    }

    override fun revert() {
        devicePolicyManager().setScreenCaptureDisabled(admin, false)
    }

    override fun isActive(): Boolean = devicePolicyManager().getScreenCaptureDisabled(admin)

    companion object {
        const val ID = "screen_capture_disabled"
    }
}
