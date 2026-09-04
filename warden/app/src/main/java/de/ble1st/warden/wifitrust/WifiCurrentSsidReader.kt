package de.ble1st.warden.wifitrust

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import de.ble1st.warden.admin.WardenDeviceAdminReceiver

/**
 * Liest die SSID des aktuell verbundenen WLANs für [WifiTrustController] (2026-09-03) — dasselbe
 * Selbstfreigabe-Muster wie [de.ble1st.warden.cellsecurity.CellObservationReader].
 *
 * `WifiManager.getConnectionInfo().getSsid()` verlangt seit Android 8.1 `ACCESS_FINE_LOCATION`
 * (grobe Standortfreigabe reicht laut Android-API-Doku ausdrücklich nicht, dieselbe Grenze wie bei
 * `getAllCellInfo()`) — ohne diese Berechtigung liefert es den Platzhalter
 * [WifiManager.UNKNOWN_SSID] statt der echten SSID. Der erneute Selbstfreigabe-Aufruf hier ist
 * bewusst redundant, falls [de.ble1st.warden.cellsecurity.CellSecurityController] dieselbe
 * Berechtigung bereits gewährt hat — `setPermissionGrantState` ist idempotent, und dieser Reader
 * darf nicht davon abhängen, dass jenes Feature aktiviert ist.
 *
 * Prüft zusätzlich über [ConnectivityManager], ob das aktive Netzwerk überhaupt WLAN ist — ohne
 * diesen Check könnte `getConnectionInfo()` einen veralteten Stand von einer früheren WLAN-
 * Verbindung zurückgeben, obwohl das Gerät inzwischen z. B. nur noch Mobilfunkdaten nutzt.
 *
 * Liefert `null` bei jedem Fehlschlag (kein WLAN verbunden, Berechtigung fehlt, SSID nicht
 * ermittelbar) — [de.ble1st.warden.domain.wifitrust.WifiTrustDecision.evaluate] behandelt das als
 * "kein WLAN verbunden", nicht als "unbekanntes Netz" (s. dessen Klassendoc).
 */
class WifiCurrentSsidReader(private val context: Context) {

    fun currentSsid(): String? {
        ensurePermissionGranted()
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "ACCESS_FINE_LOCATION nicht erteilt — SSID nicht ermittelbar")
            return null
        }
        if (!isActiveNetworkWifi()) return null
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val rawSsid = try {
            wifiManager.connectionInfo?.ssid
        } catch (e: SecurityException) {
            // Explizites catch statt runCatching — Android Lints MissingPermission-Prüfung
            // verlangt genau diese Form, s. CLAUDE.md.
            Log.w(TAG, "SSID nicht lesbar", e)
            return null
        }
        if (rawSsid.isNullOrBlank() || rawSsid == WifiManager.UNKNOWN_SSID) return null
        // Android liefert die SSID in Anführungszeichen eingeschlossen, sofern sie als UTF-8-String
        // dekodierbar ist (der Regelfall) — für den Vergleich mit der Vertrauensliste unerheblich,
        // aber ohne das Entfernen würde jede vom Nutzer eingetragene SSID nie matchen.
        return rawSsid.removeSurrounding("\"")
    }

    private fun isActiveNetworkWifi(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun ensurePermissionGranted() {
        try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)
            dpm.setPermissionGrantState(
                admin,
                context.packageName,
                Manifest.permission.ACCESS_FINE_LOCATION,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Berechtigungs-Selbstfreigabe fehlgeschlagen", e)
        }
    }

    private companion object {
        const val TAG = "WifiCurrentSsidReader"
    }
}
