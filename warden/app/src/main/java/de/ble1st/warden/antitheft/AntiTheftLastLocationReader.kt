package de.ble1st.warden.antitheft

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import de.ble1st.warden.admin.WardenDeviceAdminReceiver
import java.util.Locale

/**
 * Liest den **zuletzt bekannten** Standort für [AntiTheftAlarmController] (2026-09-03, Ideenliste
 * "Standort-Log beim Diebstahlschutz-Alarm") — dasselbe Selbstfreigabe-Muster wie
 * [de.ble1st.warden.cellsecurity.CellObservationReader]/[de.ble1st.warden.wifitrust
 * .WifiCurrentSsidReader].
 *
 * **Bewusst kein aktiver GPS-Fix** (`LocationManager.requestLocationUpdates`/
 * `getCurrentLocation`): ein aktiver Fix kann Sekunden bis Zehner-Sekunden dauern und würde den
 * ohnehin schon zeitkritischen Alarm-Auslösepfad verzögern, ganz zu schweigen vom zusätzlichen
 * GPS-Stromverbrauch in genau dem Moment, in dem das Gerät möglicherweise gerade gestohlen wird.
 * `getLastKnownLocation` liefert stattdessen sofort den letzten vom System ohnehin schon
 * vorgehaltenen Wert — kann `null` sein (Standortdienst war nie aktiv, oder das Gerät wurde seit
 * dem letzten Systemstart noch nie geortet), das ist eine bekannte, akzeptierte Grenze dieses
 * Ansatzes, kein Fehler.
 */
class AntiTheftLastLocationReader(private val context: Context) {

    data class LastLocation(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val ageMillis: Long,
    )

    fun read(): LastLocation? {
        ensurePermissionGranted()
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "ACCESS_FINE_LOCATION nicht erteilt — kein Standort verfügbar")
            return null
        }
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = try {
            locationManager.getProviders(true)
        } catch (e: SecurityException) {
            Log.w(TAG, "Anbieterliste nicht lesbar", e)
            return null
        }
        val best = providers.mapNotNull { provider ->
            try {
                locationManager.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                // Explizites catch statt runCatching — Android Lints MissingPermission-Prüfung
                // verlangt genau diese Form, s. CLAUDE.md.
                null
            }
        }.maxByOrNull { it.time } ?: return null

        return LastLocation(
            latitude = best.latitude,
            longitude = best.longitude,
            accuracyMeters = if (best.hasAccuracy()) best.accuracy else null,
            ageMillis = (System.currentTimeMillis() - best.time).coerceAtLeast(0L),
        )
    }

    /** Reine Formatierung für die Audit-Log-Zeile — kein Reverse-Geocoding (keine Netzabhängigkeit,
     * s. Projektkonvention "offline-first"), nur Koordinaten plus grobe Alters-/Genauigkeitsangabe. */
    fun describe(location: LastLocation): String {
        val ageSeconds = location.ageMillis / 1000
        val accuracyText = location.accuracyMeters?.let { String.format(Locale.ROOT, ", Genauigkeit ±%.0fm", it) } ?: ""
        return String.format(Locale.ROOT, "%.5f, %.5f (vor %ds erfasst%s)", location.latitude, location.longitude, ageSeconds, accuracyText)
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
        const val TAG = "AntiTheftLastLocationReader"
    }
}
