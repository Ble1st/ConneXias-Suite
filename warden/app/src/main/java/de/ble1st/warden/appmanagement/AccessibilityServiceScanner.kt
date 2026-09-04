package de.ble1st.warden.appmanagement

import android.content.Context
import android.view.accessibility.AccessibilityManager

/**
 * Milestone "Manifest-Scan + Sofort-Benachrichtigung" (2026-08-21, auf Nutzerwunsch, s.
 * [DeviceAdminCapabilityScanner]-Klassendoc für dieselbe Begründung) — liest jetzt alle
 * *installierten* (`getInstalledAccessibilityServiceList`), nicht mehr nur die vom Nutzer bereits
 * *aktivierten* Accessibility-Services (`getEnabledAccessibilityServiceList`). Ein Service, der
 * nur im Manifest deklariert, aber noch nie in den Bedienungshilfen-Einstellungen eingeschaltet
 * wurde, ist proaktiv genauso ein Verdachtssignal — und lässt sich, anders als ein bereits
 * aktivierter, noch zuverlässig automatisch einfrieren/deinstallieren.
 *
 * Kein Sonderrecht nötig — jede App darf diese Liste abfragen, sie beschreibt nur, was auf dem
 * Gerät installiert ist. Bekannte Missbrauchsmasche (Overlay-/Klickjacking-Trojaner): ein
 * aktivierter Accessibility-Service kann Bildschirminhalte lesen, Touch-Events simulieren und
 * z. B. Deinstallations-/Berechtigungsdialoge automatisch wegklicken oder den Bildschirm mit
 * einem Overlay blockieren. Reiner Lesezugriff, keine Mutation.
 */
class AccessibilityServiceScanner(private val context: Context) {

    fun declaredAccessibilityServicePackageNames(): Set<String> {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return emptySet()
        return manager.getInstalledAccessibilityServiceList()
            .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
            .toSet()
    }
}
