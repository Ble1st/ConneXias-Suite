package de.ble1st.warden.registry

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import de.ble1st.warden.admin.WardenDeviceAdminReceiver

/**
 * Tier 6 ("Kosmetik", 2026-08-22) — wraps `DevicePolicyManager.setLongSupportMessage`/
 * `getLongSupportMessage`: ein Hinweistext, den das OS anzeigt, wenn eine Aktion durch
 * Device-Owner-Richtlinien blockiert wird (z. B. in Einstellungen > Geräteadministrator-App-Info)
 * — z. B. ein Kontakt-/Rückgabehinweis, ergänzend zu [LockScreenInfoManager] (dort:
 * Sperrbildschirm) und [OrganizationNameManager] (dort: fester Banner-Satz). Gleiches
 * `apply`/`current`-Muster wie beide, kein [de.ble1st.warden.domain.registry.Safeguard] (Freitext
 * statt Boolean-"an/aus", dieselbe Begründung wie dort).
 *
 * **Bewusst nur "long", kein `setShortSupportMessage`:** das Kurzformat hat laut Android-Doku ein
 * sehr kleines Zeichenbudget und wird nur an wenigen, selten sichtbaren Stellen angezeigt —
 * geringer Mehrwert für den hier verfolgten Zweck ("Kontakt-/Rückgabehinweis lesbar machen"),
 * deshalb bewusst nicht zusätzlich als eigenes UI-Feld exponiert.
 */
class SupportMessageManager(context: Context) {
    private val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)
    private val dpm = checkNotNull(context.getSystemService(DevicePolicyManager::class.java)) {
        "DevicePolicyManager nicht verfügbar"
    }

    fun apply(text: String?) {
        dpm.setLongSupportMessage(admin, text)
    }

    fun current(): String? = dpm.getLongSupportMessage(admin)?.toString()?.takeIf { it.isNotEmpty() }
}
