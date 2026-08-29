package de.ble1st.warden.cellsecurity

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import android.util.Log
import de.ble1st.warden.admin.WardenDeviceAdminReceiver
import de.ble1st.warden.domain.cellsecurity.CellGeneration
import de.ble1st.warden.domain.cellsecurity.CellObservation

/**
 * Liest die aktuell *registrierte* Mobilfunkzelle für [CellSecurityController]/
 * [de.ble1st.warden.domain.cellsecurity.CellSecurityDecision] (2026-08-29) — dasselbe
 * Selbstfreigabe-Muster wie [de.ble1st.warden.sim.SimFingerprintReader].
 *
 * **Zwei Berechtigungen, nicht nur eine:** `TelephonyManager.getAllCellInfo()` verlangt seit
 * Android 10 zusätzlich zu `READ_PHONE_STATE` auch `ACCESS_FINE_LOCATION` (grobe Standortfreigabe
 * reicht laut Android-API-Doku ausdrücklich nicht) — Zellstandort-Information gilt seitdem selbst
 * als Standort-Datum. Beide werden hier als Device Owner selbst gewährt, dasselbe Muster wie
 * `READ_PHONE_STATE` in [de.ble1st.warden.sim.SimFingerprintReader]. Der erneute Selbstfreigabe-
 * Aufruf für `READ_PHONE_STATE` hier ist bewusst redundant (die SIM-Erkennung gewährt es bereits) —
 * `setPermissionGrantState` ist idempotent, und dieser Reader darf nicht davon abhängen, dass
 * `SimFingerprintReader` zuerst gelaufen ist.
 *
 * **Standortdienst kann trotz Berechtigung leer liefern:** ist der System-Standortschalter aus,
 * liefert `getAllCellInfo()` laut Android-API-Doku eine leere Liste — ganz unabhängig von der
 * Berechtigung. Ohne den expliziten [LocationManager.isLocationEnabled]-Check davor sähe das wie
 * "kein Empfang" statt "vom System blockiert" aus.
 *
 * `observe()` liefert `null` bei jedem Fehlschlag (fehlende Berechtigung, Standort aus, kein
 * `isRegistered`-Eintrag) — anders als [de.ble1st.warden.sim.SimFingerprintReader] gibt es hier
 * keine Dreiteilung "nicht lesbar"/"leer, aber echtes Signal"/"Wert": eine leere Zell-Liste ist
 * hier immer nur "nicht ermittelbar", nie selbst ein Sicherheitssignal.
 */
class CellObservationReader(private val context: Context) {

    fun observe(): CellObservation? {
        ensurePermissionsGranted()
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_PHONE_STATE/ACCESS_FINE_LOCATION nicht erteilt — Zellinfo nicht ermittelbar")
            return null
        }
        val locationManager = context.getSystemService(LocationManager::class.java)
        if (locationManager?.isLocationEnabled != true) {
            Log.w(TAG, "Standortdienst deaktiviert — Zellinfo laut Android-API nicht verfügbar")
            return null
        }
        val telephonyManager = context.getSystemService(TelephonyManager::class.java) ?: return null
        val cellInfos = try {
            telephonyManager.allCellInfo.orEmpty()
        } catch (e: SecurityException) {
            // Explizites catch statt runCatching — Android Lints MissingPermission-Prüfung
            // verlangt genau diese Form, s. CLAUDE.md.
            Log.w(TAG, "Zellinfo nicht lesbar", e)
            return null
        }
        val registered = cellInfos.firstOrNull { it.isRegistered } ?: return null
        return mapRegisteredCell(registered)
    }

    private fun mapRegisteredCell(cellInfo: CellInfo): CellObservation? {
        val dbm = cellInfo.cellSignalStrength?.dbm
        return when (val identity = cellInfo.cellIdentity) {
            is CellIdentityGsm -> CellObservation(
                mcc = identity.mccString,
                mnc = identity.mncString,
                cellId = unavailableToNull(identity.cid)?.toLong(),
                areaCode = unavailableToNull(identity.lac),
                generation = CellGeneration.GSM_2G,
                signalDbm = dbm,
            )
            is CellIdentityWcdma -> CellObservation(
                mcc = identity.mccString,
                mnc = identity.mncString,
                cellId = unavailableToNull(identity.cid)?.toLong(),
                areaCode = unavailableToNull(identity.lac),
                generation = CellGeneration.UMTS_3G,
                signalDbm = dbm,
            )
            is CellIdentityLte -> CellObservation(
                mcc = identity.mccString,
                mnc = identity.mncString,
                cellId = unavailableToNull(identity.ci)?.toLong(),
                areaCode = unavailableToNull(identity.tac),
                generation = CellGeneration.LTE_4G,
                signalDbm = dbm,
            )
            is CellIdentityNr -> CellObservation(
                mcc = identity.mccString,
                mnc = identity.mncString,
                cellId = identity.nci.takeIf { it != CellInfo.UNAVAILABLE_LONG },
                areaCode = unavailableToNull(identity.tac),
                generation = CellGeneration.NR_5G,
                signalDbm = dbm,
            )
            // CDMA (kein MCC/MNC-Konzept) und künftige, hier noch unbekannte Zelltypen — bewusst
            // kein Rateversuch/Fallback, lieber "nicht ermittelbar" als eine erfundene Zuordnung.
            else -> null
        }
    }

    private fun unavailableToNull(value: Int): Int? = value.takeIf { it != CellInfo.UNAVAILABLE }

    private fun ensurePermissionsGranted() {
        try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)
            for (permission in REQUIRED_PERMISSIONS) {
                dpm.setPermissionGrantState(
                    admin,
                    context.packageName,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Berechtigungs-Selbstfreigabe fehlgeschlagen", e)
        }
    }

    private companion object {
        const val TAG = "CellObservationReader"
        val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}
