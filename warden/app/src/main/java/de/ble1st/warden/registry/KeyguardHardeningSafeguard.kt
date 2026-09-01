package de.ble1st.warden.registry

import android.app.admin.DevicePolicyManager
import android.content.Context

/**
 * Tier 2 ("Anti-Diebstahl/Fernsperre", 2026-08-22) — bewusst lokal, kein Standort/Fernzugriff:
 * schaltet Trust Agents (Smart Lock) und biometrische Entsperrung (Fingerabdruck/Gesicht) über
 * `DevicePolicyManager.setKeyguardDisabledFeatures`/`getKeyguardDisabledFeatures` ab, sodass nur
 * noch PIN/Passwort/Muster den Sperrbildschirm öffnen. Ergänzt [SensitiveAction.LOCK_NOW]
 * ([de.ble1st.warden.domain.presence.SensitiveAction]): "sofort sperren" bringt wenig, wenn ein
 * Trust Agent (z. B. Standort-basiertes Smart Lock) die Sperre gleich wieder aufhebt.
 *
 * Bitmaske statt Boolean — `apply()`/`revert()` verändern nur die eigenen Bits additiv
 * (Read-Modify-Write über `getKeyguardDisabledFeatures`), **nicht** die volle Maske: seit
 * [LockScreenPrivacySafeguard] (dieselbe API, andere, überschneidungsfreie Bits) einen zweiten
 * unabhängigen Nutzer derselben Maske hat, würde ein hartes Überschreiben der vollen Maske dessen
 * Zustand bei jedem `apply()`/`revert()` dieser Klasse zerstören und umgekehrt.
 * Reversibel über dieselbe API, kein Rückbau-Risiko — regulär über
 * [de.ble1st.warden.ui.SafeguardsScreen] umschaltbar.
 */
class KeyguardHardeningSafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        val current = devicePolicyManager().getKeyguardDisabledFeatures(admin)
        devicePolicyManager().setKeyguardDisabledFeatures(admin, current or DISABLED_FEATURES)
    }

    override fun revert() {
        val current = devicePolicyManager().getKeyguardDisabledFeatures(admin)
        devicePolicyManager().setKeyguardDisabledFeatures(admin, current and DISABLED_FEATURES.inv())
    }

    override fun isActive(): Boolean =
        devicePolicyManager().getKeyguardDisabledFeatures(admin) and DISABLED_FEATURES == DISABLED_FEATURES

    companion object {
        const val ID = "keyguard_hardening"

        private const val DISABLED_FEATURES =
            DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS or
                DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT or
                DevicePolicyManager.KEYGUARD_DISABLE_FACE
    }
}
