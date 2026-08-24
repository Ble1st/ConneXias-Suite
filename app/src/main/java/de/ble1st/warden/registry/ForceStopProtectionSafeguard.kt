package de.ble1st.warden.registry

import android.content.Context

/**
 * Tier 1 ("Anti-Tamper", 2026-08-22): verhindert, dass die Nutzerin (oder wer auch immer gerade
 * Zugriff auf die Systemeinstellungen hat) Warden über "Erzwinge Stopp" oder Akku-Optimierung
 * lahmlegt — über `DevicePolicyManager.setUserControlDisabledPackages`/
 * `getUserControlDisabledPackages`. Ein gestoppter Verdachtsscanner
 * ([de.ble1st.warden.appmanagement.SuspiciousAppScanController]) scannt nichts mehr; dieser
 * Safeguard schützt die Verfügbarkeit der App selbst, nicht den Gerätezustand.
 *
 * **Verwaltet die Liste exklusiv für Wardens eigenes Paket:** `setUserControlDisabledPackages`
 * nimmt eine vollständige Ersetzungs-Liste entgegen, kein additives `add`/`remove` — [apply]
 * setzt die Liste auf genau `[eigenes Paket]`, [revert] auf die leere Liste. Das ist unkritisch,
 * solange kein anderer Aufrufer in diesem Projekt dieselbe API für andere Pakete nutzt (aktuell
 * der Fall) — sollte künftig ein zweiter Nutzer dieser API dazukommen, muss diese Klasse additiv
 * statt ersetzend umgebaut werden.
 *
 * Reversibel über dieselbe API, kein Rückbau-Risiko wie `DeviceLockdownBundle` — regulär über
 * [de.ble1st.warden.ui.SafeguardsScreen] umschaltbar.
 */
class ForceStopProtectionSafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        devicePolicyManager().setUserControlDisabledPackages(admin, listOf(context.packageName))
    }

    override fun revert() {
        devicePolicyManager().setUserControlDisabledPackages(admin, emptyList())
    }

    override fun isActive(): Boolean =
        devicePolicyManager().getUserControlDisabledPackages(admin).contains(context.packageName)

    companion object {
        const val ID = "force_stop_protection"
    }
}
