package de.ble1st.warden.appmanagement

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.view.accessibility.AccessibilityManager

/**
 * Milestone "weitere Funktionen für den Sicherheitsscanner" (2026-08-22, Feature 5) — liest den
 * tatsächlich *aktiven* (nicht nur deklarierten) Geräteadmin-/Bedienungshilfen-Zustand, als
 * Eingabe für [de.ble1st.warden.domain.appmanagement.ActivationTransitionDecision]. Anders als
 * [DeviceAdminCapabilityScanner]/[AccessibilityServiceScanner] (bewusst manifest-basiert, s.
 * dortige Klassendocs) geht es hier gerade **nicht** um proaktive Vor-Aktivierungs-Erkennung,
 * sondern um den Übergang selbst — beide Sichten ergänzen sich.
 *
 * `DevicePolicyManager.getActiveAdmins()` braucht keinen Device-Owner-Kontext (anders als
 * [de.ble1st.warden.registry.DpmSafeguard]s Aufrufe) — jede App darf abfragen, welche
 * Admin-Komponenten für den aktuellen Nutzer aktiv sind, dieselbe Sichtbarkeit wie
 * Einstellungen > Geräteadministrator-Apps.
 */
class ActiveCapabilityReader(private val context: Context) {

    fun activeDeviceAdminPackageNames(): Set<String> {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return emptySet()
        return dpm.activeAdmins.orEmpty().mapNotNull { it.packageName }.toSet()
    }

    fun activeAccessibilityServicePackageNames(): Set<String> {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return emptySet()
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
            .toSet()
    }
}
