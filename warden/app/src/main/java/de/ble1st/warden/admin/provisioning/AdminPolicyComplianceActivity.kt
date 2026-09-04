package de.ble1st.warden.admin.provisioning

import android.app.Activity
import android.os.Bundle
import android.util.Log
import de.ble1st.warden.domain.profile.WardenProfile
import de.ble1st.warden.domain.registry.SafeguardRegistry
import de.ble1st.warden.logging.HashChainLogStore
import de.ble1st.warden.registry.PersistentSafeguardRegistry
import de.ble1st.warden.registry.RegistryStorage
import de.ble1st.warden.registry.SafeguardCatalog
import de.ble1st.warden.registry.SafeguardRegistryStore
import de.ble1st.warden.registry.WardenProfileApplier
import de.ble1st.warden.usb.UsbAutoLockStorage
import de.ble1st.warden.usb.UsbLockStateReceiver
import de.ble1st.warden.wardenAuditLog

/**
 * Second mandatory Android 11+ provisioning handler: the setup wizard starts this activity via
 * [android.app.admin.DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE] after Warden is set as
 * Device Owner. Applies the Alltag profile (reset-path + lock-screen quick-access hardening,
 * USB auto-lock on lock) so a freshly provisioned device is not left on an empty catalog.
 *
 * Always returns RESULT_OK — a partial DPM failure must not stick the wizard. **Aber** ein
 * Teil-Misserfolg wird zusätzlich in den Audit-Log ([HashChainLogStore]) eingetragen, damit der
 * Operator ihn später im Warden-Status sieht — nur Logcat wäre für den Operator unsichtbar.
 * Lockdown / USB debug-kill / wipeData are not applied here.
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
        // isLockdownActive bleibt auf ihrem Default (immer false) — ein frisch per QR
        // provisioniertes Gerät kann das presence-gated Lockdown-Bündel noch gar nicht scharf
        // haben.
        val result = WardenProfileApplier(
            context = this,
            registry = registry,
            setUsbAutoLock = { enabled -> UsbAutoLockStorage.setEnabled(this, enabled) },
        ).apply(WardenProfile.ALLTAG)
        UsbLockStateReceiver.syncRegistration(this)
        val logStore = wardenAuditLog(this)
        when {
            result.failed.isNotEmpty() -> {
                Log.w(TAG, "ADMIN_POLICY_COMPLIANCE: Alltag teilweise fehlgeschlagen: ${result.failed}")
                // Operator-sichtbarer Eintrag: ein frisch provisioniertes Gerät mit nur
                // teilweise angewandter Härtung ist ein echtes Sicherheitsrisiko, das im
                // Warden-Status erkennbar sein muss, nicht nur in Logcat.
                logStore.append(
                    priority = Log.WARN,
                    tag = TAG,
                    message = "provisioning: ${result.failed.size} safeguard(s) failed to apply: ${result.failed.joinToString()}",
                )
            }
            result.skipped.isNotEmpty() -> {
                Log.w(TAG, "ADMIN_POLICY_COMPLIANCE: Alltag ohne FRP-Konten angewendet, übersprungen: ${result.skipped}")
                logStore.append(
                    priority = Log.INFO,
                    tag = TAG,
                    message = "provisioning: ${result.skipped.size} safeguard(s) skipped (no FRP accounts yet): ${result.skipped.joinToString()}",
                )
            }
            else ->
                Log.i(TAG, "ADMIN_POLICY_COMPLIANCE: Alltag-Profil angewendet")
        }
    }

    private companion object {
        const val TAG = "WardenProvisioning"
    }
}
