package de.ble1st.warden.registry

import android.content.Context

/**
 * Tier 3 ("App-Kontrolle", 2026-08-22) — dasselbe Prinzip wie [AccessibilityLockdownSafeguard],
 * gegen bösartige Dritt-Tastaturen (Keylogger-Vektor): sperrt über
 * `DevicePolicyManager.setPermittedInputMethods`/`getPermittedInputMethods` jede Eingabemethode
 * außer der systemeigenen. Eine leere Liste bedeutet "nur die systemeigene Standardtastatur
 * erlaubt"; `revert()` setzt `null` (keine Einschränkung).
 *
 * `apply()` kann scheitern, wenn gerade eine *aktive* Dritt-Tastatur läuft — bewusst als
 * [IllegalStateException] geworfen statt eines stillen Fake-Erfolgs, dieselbe Begründung wie bei
 * [AccessibilityLockdownSafeguard]. Reversibel, regulär über
 * [de.ble1st.warden.ui.SafeguardsScreen] umschaltbar.
 */
class InputMethodLockdownSafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        check(devicePolicyManager().setPermittedInputMethods(admin, emptyList())) {
            "Sperre abgelehnt — vermutlich ist gerade eine Dritt-Tastatur aktiv"
        }
    }

    override fun revert() {
        devicePolicyManager().setPermittedInputMethods(admin, null)
    }

    override fun isActive(): Boolean =
        devicePolicyManager().getPermittedInputMethods(admin)?.isEmpty() == true

    companion object {
        const val ID = "input_method_lockdown"
    }
}
