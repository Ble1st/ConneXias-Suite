package de.ble1st.warden.admin.provisioning

import android.app.Activity
import android.os.Bundle
import android.util.Log
import de.ble1st.warden.domain.profile.WardenProfile
import de.ble1st.warden.domain.registry.SafeguardRegistry
import de.ble1st.warden.registry.PersistentSafeguardRegistry
import de.ble1st.warden.registry.RegistryStorage
import de.ble1st.warden.registry.SafeguardCatalog
import de.ble1st.warden.registry.SafeguardRegistryStore
import de.ble1st.warden.registry.WardenProfileApplier
import de.ble1st.warden.usb.UsbAutoLockStorage
import de.ble1st.warden.usb.UsbLockStateReceiver

/**
 * Second mandatory Android 11+ provisioning handler: the setup wizard starts this activity via
 * [android.app.admin.DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE] after Warden is set as
 * Device Owner. Applies the Alltag profile (reset-path + lock-screen quick-access hardening,
 * USB auto-lock on lock) so a freshly provisioned device is not left on an empty catalog.
 *
 * Always returns RESULT_OK — a partial DPM failure must not stick the wizard. Lockdown / USB
 * debug-kill / wipeData are not applied here.
 */
class AdminPolicyComplianceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { applyAlltagDefaults() }
            .onFailure { Log.e(TAG, "Alltag-Grundkonfiguration fehlgeschlagen", it) }
        setResult(RESULT_OK)
        finish()
    }

    private fun applyAlltagDefaults() {
        val registry = PersistentSafeguardRegistry(
            SafeguardRegistry(),
            SafeguardRegistryStore(RegistryStorage.buildEnvelopeFile(this)),
        )
        SafeguardCatalog.registerReversible(registry, this)
        registry.load()
        val result = WardenProfileApplier(this, registry) { enabled ->
            UsbAutoLockStorage.setEnabled(this, enabled)
        }.apply(WardenProfile.ALLTAG)
        UsbLockStateReceiver.syncRegistration(this)
        when {
            result.failed.isNotEmpty() ->
                Log.w(TAG, "ADMIN_POLICY_COMPLIANCE: Alltag teilweise fehlgeschlagen: ${result.failed}")
            result.skipped.isNotEmpty() ->
                Log.w(TAG, "ADMIN_POLICY_COMPLIANCE: Alltag ohne FRP-Konten angewendet, übersprungen: ${result.skipped}")
            else ->
                Log.i(TAG, "ADMIN_POLICY_COMPLIANCE: Alltag-Profil angewendet")
        }
    }

    private companion object {
        const val TAG = "WardenProvisioning"
    }
}
