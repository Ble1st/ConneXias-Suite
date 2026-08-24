package de.ble1st.warden.registry

import android.content.Context

/**
 * Tier 3 ("App-Kontrolle", 2026-08-22) — direkte Ergänzung zum Verdachtsscanner
 * ([de.ble1st.warden.appmanagement.AccessibilityServiceScanner]): der Scanner *meldet* nur
 * verdächtige, im Manifest deklarierte Bedienungshilfen-Dienste; dieser Safeguard *sperrt* aktiv
 * jeden Bedienungshilfen-Dienst außer den systemeigenen, über
 * `DevicePolicyManager.setPermittedAccessibilityServices`/`getPermittedAccessibilityServices`.
 * Bedienungshilfen-Dienste sind ein bekannter Keylogger-/Screen-Scraping-Vektor — im
 * Ernstfall (Verdacht auf Kompromittierung) lässt sich hiermit jeder Dritt-Dienst hart
 * abschalten, nicht nur der bereits als verdächtig erkannte.
 *
 * Eine leere Liste (statt `null`) bedeutet laut Android-API "nur systemeigene Dienste erlaubt" —
 * das ist die eigentliche Sperre; `null` bei [revert] hebt die Einschränkung vollständig auf.
 * `apply()` kann fehlschlagen (Rückgabewert `false`), wenn gerade ein *aktivierter*
 * Dritt-Bedienungshilfen-Dienst existiert, der durch die neue, leere Liste nicht mehr erlaubt
 * wäre — bewusst als [IllegalStateException] geworfen statt still zu tun, als wäre die Sperre
 * gelungen (Fail-Safe, Invariante 6).
 *
 * Reversibel über dieselbe API — regulär über [de.ble1st.warden.ui.SafeguardsScreen] umschaltbar,
 * kein Rückbau-Risiko wie `DeviceLockdownBundle`.
 */
class AccessibilityLockdownSafeguard(context: Context) : DpmSafeguard(context) {

    override val id: String = ID

    override fun apply() {
        check(devicePolicyManager().setPermittedAccessibilityServices(admin, emptyList())) {
            "Sperre abgelehnt — vermutlich ist gerade ein Dritt-Bedienungshilfen-Dienst aktiv"
        }
    }

    override fun revert() {
        devicePolicyManager().setPermittedAccessibilityServices(admin, null)
    }

    override fun isActive(): Boolean =
        devicePolicyManager().getPermittedAccessibilityServices(admin)?.isEmpty() == true

    companion object {
        const val ID = "accessibility_lockdown"
    }
}
