package de.ble1st.warden.registry

import android.app.admin.FactoryResetProtectionPolicy
import android.content.Context
import android.content.Intent

/**
 * Enterprise Factory Reset Protection: after an **untrusted** wipe (typically Recovery), the
 * device can only be provisioned by the stored unlock accounts. This does not block Recovery
 * itself — it makes a Recovery wipe useless to a thief without those accounts.
 *
 * Never applied with an empty account list (that can brick the device). Revert clears the policy
 * (`null`). After each DPM write, GMS is notified via the documented FRP_CONFIG_CHANGED broadcast
 * so the FRP agent picks up the change on devices that ship Google Play services.
 *
 * **Grenzen (bewusst nicht durch diese Klasse absicherbar):** wirkt nur, solange der Bootloader
 * gesperrt bleibt — bei entsperrtem Bootloader (`fastboot flashing unlock`/Custom Recovery) kann
 * die Datenpartition ohne jeden FRP-Checkpoint neu beschrieben werden, unabhängig von dieser
 * Policy; es gibt keine öffentliche Device-Owner-API, die das zusätzlich verhindert (s. auch
 * den OEM-Unlock-Hinweis in [de.ble1st.warden.ui.SafeguardsScreen]). Außerdem setzt Enforcement Google Play Services
 * voraus, das den Broadcast unten empfängt — [isActive] spiegelt nur den DPM-seitig
 * gespeicherten Policy-Zustand, nicht ob der FRP-Agent ihn tatsächlich übernommen hat; für diesen
 * zweiten Punkt s. [isFrpAgentAvailable].
 *
 * **⚠ Empirisch widerlegt auf echter Hardware (2026-08-25, Samsung SM-A156B, s. Projekt-Notiz zum
 * Testgerät):** Policy korrekt über DPM gesetzt (`dumpsys device_policy` bestätigte
 * `factoryResetProtectionEnabled=true` mit der hinterlegten Adresse) — trotzdem kam ein direkt
 * danach ausgelöster echter Recovery-Wipe (gesperrter Bootloader, kein OEM-Unlock) ohne jede
 * Konto-Abfrage durch: Gerät kam komplett unprotected/neu provisionierbar zurück. Deckt sich mit
 * der oben schon vermuteten Lücke — vermutlich hat der asynchrone GMS-FRP-Agent den Broadcast
 * nicht (rechtzeitig) verarbeitet, bevor der Wipe lief, denkbar ist aber auch eine grundsätzliche
 * Lücke in Samsungs Umsetzung der Enterprise-FRP-API. **Nicht als verlässlichen Diebstahlschutz
 * behandeln, bis das auf echter Ziel-Hardware mit ausreichend Abstand zwischen Aktivierung und
 * Wipe erneut verifiziert wurde** — `isActive()`/die UI können den tatsächlichen
 * Durchsetzungszustand strukturell nicht von außen prüfen.
 */
class FactoryResetProtectionSafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        val accounts = WardenFactoryResetProtectionStorage.load(context)
        if (accounts.isEmpty()) {
            throw IllegalStateException("Factory reset protection needs at least one unlock account")
        }
        val policy = FactoryResetProtectionPolicy.Builder()
            .setFactoryResetProtectionAccounts(accounts)
            .setFactoryResetProtectionEnabled(true)
            .build()
        devicePolicyManager().setFactoryResetProtectionPolicy(admin, policy)
        notifyFrpAgent()
    }

    override fun revert() {
        devicePolicyManager().setFactoryResetProtectionPolicy(admin, null)
        notifyFrpAgent()
    }

    override fun isActive(): Boolean {
        val policy = devicePolicyManager().getFactoryResetProtectionPolicy(admin) ?: return false
        return policy.isFactoryResetProtectionEnabled &&
            policy.factoryResetProtectionAccounts.isNotEmpty()
    }

    /**
     * Diagnostic only, not part of [de.ble1st.warden.domain.registry.Safeguard]: whether GMS is
     * even installed to receive [ACTION_FRP_CONFIG_CHANGED]. [isActive] cannot see this — it only
     * confirms the DPM-side write succeeded, not that the FRP agent picked the policy up. Callers
     * (UI) use this to warn separately, since a missing GMS makes [notifyFrpAgent]'s broadcast a
     * silent no-op and the toggle would otherwise look "active" while unenforced.
     */
    fun isFrpAgentAvailable(): Boolean =
        runCatching { context.packageManager.getPackageInfo(GMS_PACKAGE, 0) }.isSuccess

    private fun notifyFrpAgent() {
        if (!isFrpAgentAvailable()) return
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_FRP_CONFIG_CHANGED).setPackage(GMS_PACKAGE),
            )
        }
    }

    companion object {
        const val ID = "factory_reset_protection"
        private const val GMS_PACKAGE = "com.google.android.gms"
        private const val ACTION_FRP_CONFIG_CHANGED = "com.google.android.gms.auth.FRP_CONFIG_CHANGED"
    }
}
