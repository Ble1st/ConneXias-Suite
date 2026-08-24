package de.ble1st.warden.registry

import android.content.Context
import androidx.core.content.edit
import de.ble1st.warden.domain.frp.FactoryResetProtectionAccounts
import de.ble1st.warden.domain.frp.FactoryResetProtectionDecision

/**
 * Desired unlock accounts for [FactoryResetProtectionSafeguard]. Device-protected so boot
 * reconciliation can re-apply FRP before first unlock. Not EnvelopeFile: the values are the
 * same identifiers the setup wizard will show after an untrusted wipe.
 */
object WardenFactoryResetProtectionStorage {
    private const val PREFS_NAME = "warden_frp_accounts"
    private const val KEY_ACCOUNTS = "accounts"

    fun load(context: Context): List<String> {
        val stored = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_ACCOUNTS, emptySet())
            ?: emptySet()
        return when (val decision = FactoryResetProtectionAccounts.evaluate(stored.toList().sorted())) {
            is FactoryResetProtectionDecision.Valid -> decision.accounts
            else -> emptyList()
        }
    }

    fun save(context: Context, accounts: List<String>) {
        val normalized = when (val decision = FactoryResetProtectionAccounts.evaluate(accounts)) {
            is FactoryResetProtectionDecision.Valid -> decision.accounts
            else -> emptyList()
        }
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                if (normalized.isEmpty()) {
                    remove(KEY_ACCOUNTS)
                } else {
                    putStringSet(KEY_ACCOUNTS, normalized.toSet())
                }
            }
    }
}
