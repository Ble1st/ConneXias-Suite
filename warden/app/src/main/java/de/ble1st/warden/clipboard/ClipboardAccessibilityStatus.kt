package de.ble1st.warden.clipboard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Wie [de.ble1st.warden.performance.AppUsageReader] für den Nutzungsdatenzugriff: die
 * Bedienungshilfen-Freigabe ist eine AppOps-artige Sonderberechtigung, die **kein**
 * `DevicePolicyManager`-Silent-Grant-Pfad kennt (anders als z. B. `POST_NOTIFICATIONS`) — der
 * Nutzer muss sie manuell unter Einstellungen → Bedienungshilfen erteilen, Warden kann sie nur
 * lesen und zum richtigen Bildschirm verlinken.
 *
 * Zwei unabhängig zu prüfende Zustände (`docs/design-clipboard-guard.md` Abschnitt 3.2.7):
 * Wardens eigene App-Präferenz ([ClipboardGuardStorage.isCrossAppMonitoringEnabled], Nutzer will
 * die Funktion) und der tatsächliche Systemzustand ([isServiceEnabled], Nutzer hat die
 * Bedienungshilfe wirklich freigegeben). Beide müssen `true` sein, damit
 * [ClipboardAccessibilityService] tatsächlich Ereignisse erfasst — die UI zeigt beide getrennt,
 * damit "App-Schalter an, aber System-Freigabe fehlt" nicht als Fehler missverstanden wird.
 */
object ClipboardAccessibilityStatus {

    fun isServiceEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
    }

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
