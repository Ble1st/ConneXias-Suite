package de.ble1st.warden.appmanagement

import android.content.Context
import androidx.core.content.edit

/**
 * Milestone "weitere Funktionen für den Sicherheitsscanner" (2026-08-22, Feature 5) — Baseline für
 * [de.ble1st.warden.domain.appmanagement.ActivationTransitionDecision]: welche Pakete beim
 * *letzten* Scan als Geräteadmin/Bedienungshilfen-Dienst aktiv waren.
 *
 * Bewusst **Klartext-`SharedPreferences`, kein [de.ble1st.warden.crypto.EnvelopeFile]** — anders
 * als der PIN-Blob ist ein verlorener/korrupter Wert hier kein Fail-Safe-Fall: die zugrunde
 * liegenden Basissignale (`EXTRA_DEVICE_ADMIN`/`ACCESSIBILITY_SERVICE_DECLARED`) feuern ohnehin
 * unabhängig davon bei jedem Scan weiter, dieser Cache liefert nur das *zusätzliche*,
 * dringlichere "gerade aktiviert"-Signal. Schlimmstenfalls also ein verpasstes Bonus-Signal, kein
 * Sicherheitsverlust.
 */
class ActivationHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** `null` = noch keine Historie vorhanden (s. [de.ble1st.warden.domain.appmanagement
     * .ActivationTransitionDecision]-Klassendoc für den Sonderfall "allererster Lauf"). */
    fun previouslyActiveDeviceAdmins(): Set<String>? = prefs.getStringSet(KEY_ADMINS, null)?.toSet()

    fun previouslyActiveAccessibilityServices(): Set<String>? = prefs.getStringSet(KEY_ACCESSIBILITY, null)?.toSet()

    fun recordActiveDeviceAdmins(packageNames: Set<String>) {
        prefs.edit { putStringSet(KEY_ADMINS, packageNames) }
    }

    fun recordActiveAccessibilityServices(packageNames: Set<String>) {
        prefs.edit { putStringSet(KEY_ACCESSIBILITY, packageNames) }
    }

    private companion object {
        const val PREFS_NAME = "warden_activation_history"
        const val KEY_ADMINS = "active_admins"
        const val KEY_ACCESSIBILITY = "active_accessibility"
    }
}
