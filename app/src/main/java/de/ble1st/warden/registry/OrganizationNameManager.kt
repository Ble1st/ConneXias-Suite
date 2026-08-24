package de.ble1st.warden.registry

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import de.ble1st.warden.admin.WardenDeviceAdminReceiver

/**
 * Setzt den Organisationsnamen über `DevicePolicyManager.setOrganizationName` (API 24+) — anders
 * als [LockScreenInfoManager]s Freitext (`setDeviceOwnerLockScreenInfo`) landet dieser Wert nicht
 * nur auf dem Sperrbildschirm: auf vollständig verwalteten ("organization-owned") Geräten
 * verwendet ihn das OS **anstelle** eines generischen Platzhaltertexts für die Pflicht-Banner-
 * Anzeige (z. B. Sperrbildschirm, Einstellungen-"Verwaltet von …"-Zeile).
 *
 * **Live entdeckt (2026-08-22, echtes Testgerät, Samsung SM-A156B/One UI):** [LockScreenInfoManager]s
 * `setDeviceOwnerLockScreenInfo`-Text wurde von der DPM korrekt gespeichert (per Getter
 * nachgewiesen), erschien aber **nicht** auf dem echten Sperrbildschirm — stattdessen zeigte
 * One UI dort fest "Dieses Gerät gehört deiner Organisation" (`isOrganizationOwnedDevice=true`).
 * Dieser generische Text ist laut Android-Enterprise-Doku der **Default, solange kein
 * Organisationsname gesetzt ist** — mit gesetztem Namen wird daraus "Dieses Gerät gehört zu
 * \<Name\>". Beide APIs bleiben trotzdem unabhängig persistierte, separate DPM-Felder — deshalb
 * eine eigene, kleine Klasse statt einer Erweiterung von [LockScreenInfoManager].
 *
 * Anders als [LockScreenInfoManager.current] braucht [DevicePolicyManager.getOrganizationName]
 * laut Android-API eine `admin`-Komponente als Parameter (nicht global abfragbar).
 *
 * `apply(null)`/`apply("")` löscht den Namen wieder.
 */
class OrganizationNameManager(context: Context) {

    private val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)
    private val dpm = checkNotNull(context.getSystemService(DevicePolicyManager::class.java)) {
        "DevicePolicyManager nicht verfügbar"
    }

    fun apply(name: String?) {
        dpm.setOrganizationName(admin, name ?: "")
    }

    /** Immer live von der DPM abgefragt, nie gecacht — dieselbe Fail-Safe-Haltung wie
     * [LockScreenInfoManager.current]. */
    fun current(): String? = dpm.getOrganizationName(admin)?.toString()?.takeIf { it.isNotEmpty() }
}
