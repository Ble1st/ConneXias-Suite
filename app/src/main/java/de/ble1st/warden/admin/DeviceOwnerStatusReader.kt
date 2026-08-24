package de.ble1st.warden.admin

import android.app.admin.DevicePolicyManager
import android.content.Context

/**
 * Liest den DO-Status zur Laufzeit vom System — Konzept Abschnitt 3 ("Zustandsprüfung: DPM
 * Restrictions: Wahrheit im System, keine eigene Speicherung"). Gilt auch hier: kein eigener
 * Cache, jede Abfrage geht live an [DevicePolicyManager].
 *
 * Bewusst minimal (Meilenstein A.5) — noch kein Domain-Interface in `:core:domain`, da es bislang
 * nur diese eine Abfrage gibt; Abstraktion folgt, sobald es mehr als einen Aufrufer/Testfall gibt.
 */
class DeviceOwnerStatusReader(private val context: Context) {

    fun isDeviceOwner(): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return dpm.isDeviceOwnerApp(context.packageName)
    }
}
