package de.ble1st.warden.registry

import android.content.Context
import de.ble1st.warden.domain.profile.WardenProfile
import de.ble1st.warden.domain.profile.WardenProfileApplyAction
import de.ble1st.warden.domain.profile.WardenProfileApplyDecision
import de.ble1st.warden.domain.profile.WardenProfileSpec

/**
 * Result of [WardenProfileApplier.apply]. [failed] are IDs whose `apply()`/`revert()` threw.
 * [skipped] are IDs that were deliberately *not* applied even though the profile wants them on —
 * currently only [FactoryResetProtectionSafeguard] with no unlock accounts stored. Distinct from
 * [failed] because nothing went wrong technically; the caller (UI) should still surface it so a
 * profile that looks "applied" isn't silently incomplete.
 */
data class WardenProfileApplyResult(
    val failed: List<String>,
    val skipped: List<String>,
)

/**
 * Applies a [WardenProfile] onto the reversible catalog: IDs in the spec are applied, every
 * other registered reversible ID is reverted. Failures are isolated so one DPM rejection
 * (USB signaling unsupported, missing DO) does not abort the rest.
 *
 * [FactoryResetProtectionSafeguard] is skipped (and reverted) when no unlock accounts are
 * stored — applying FRP with an empty list would brick the device after an untrusted wipe.
 *
 * Does not *apply/engage* [DeviceLockdownBundle] itself — that stays presence-gated. It does,
 * since analyse.md (2. Durchgang, Hoch — "Profile knacken Presence-Lockdown über geteilten
 * DPM-Zustand"), check whether the bundle is currently armed via [isLockdownActive] and, if so,
 * leaves the four catalog IDs the bundle shares with it alone instead of silently reverting them
 * — s. [WardenProfileApplyDecision.LOCKDOWN_SHARED_IDS]-Klassendoc for the full mechanism.
 */
class WardenProfileApplier(
    private val context: Context,
    private val registry: PersistentSafeguardRegistry,
    private val setUsbAutoLock: (Boolean) -> Unit,
    private val isLockdownActive: () -> Boolean = { false },
) {

    fun apply(profile: WardenProfile): WardenProfileApplyResult {
        val wantOn = WardenProfileSpec.idsOn(profile)
        val frpAccountsConfigured = WardenFactoryResetProtectionStorage.load(context).isNotEmpty()
        val lockdownActive = isLockdownActive()
        val failed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (id in registry.registeredIds()) {
            // analyse.md (2. Durchgang, Hoch): vorher revertierte diese Schleife jede registrierte
            // ID, die nicht im Profil steht — traf auch sentinel_uninstall_protection (steht
            // absichtlich in keinem Profil, s. WardenProfileApplyDecision-Klassendoc) UND jede der
            // vier DeviceLockdownBundle-geteilten IDs, solange das Bündel presence-armed war (traf
            // debugging_features_disabled bei JEDEM Profil, usb_data_signaling_disabled bei
            // Alltag). Jeder Profilwechsel schaltete beides ohne erneute Presence-Prüfung ab.
            if (WardenProfileApplyDecision.actionFor(id, wantOn, lockdownActive) == WardenProfileApplyAction.LEAVE_UNTOUCHED) continue
            val skipFrp = id == FactoryResetProtectionSafeguard.ID && id in wantOn && !frpAccountsConfigured
            val outcome = runCatching {
                when {
                    skipFrp -> registry.revert(id)
                    id in wantOn -> registry.apply(id)
                    else -> registry.revert(id)
                }
            }
            when {
                outcome.isFailure -> failed += id
                skipFrp -> skipped += id
            }
        }
        setUsbAutoLock(WardenProfileSpec.usbAutoLockEnabled(profile))
        return WardenProfileApplyResult(failed, skipped)
    }
}
