package de.ble1st.warden.tracker

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import de.ble1st.warden.admin.WardenDeviceAdminReceiver
import java.util.concurrent.ConcurrentHashMap

/**
 * Ein einzelnes, zeitlich begrenztes BLE-Scan-Fenster für [BleTrackerController] (2026-09-03) —
 * dasselbe Selbstfreigabe-Muster wie [de.ble1st.warden.cellsecurity.CellObservationReader], hier
 * für `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` (API 31+, seitdem beide Laufzeit-Berechtigungen).
 *
 * **Bewusst ein blockierender Scan mit `Thread.sleep`, kein `CoroutineWorker`** — läuft
 * ausschließlich aus [BleTrackerWorker.doWork] heraus, das ohnehin bereits auf WorkManagers
 * eigenem Hintergrund-Thread-Pool läuft; ein mehrsekündiges Scan-Fenster ist damit unproblematisch
 * (WorkManager toleriert deutlich längere `doWork()`-Läufe), anders als die sonst in diesem Projekt
 * übliche "kurz und synchron"-Erwartung an einen `Worker` (s. [de.ble1st.warden.appmanagement
 * .SuspiciousAppScanWorker]-Klassendoc) — eine bewusste, dokumentierte Ausnahme, kein Widerspruch:
 * ein aktiver Funk-Scan braucht per Definition eine gewisse Beobachtungsdauer, anders als ein reiner
 * `PackageManager`-Lesezugriff.
 *
 * `usesPermissionFlags="neverForLocation"` im Manifest bedeutet: dieser Scan liefert absichtlich
 * keine für Standortzwecke geeigneten Ergebnisse (Android erzwingt das durch eine gefilterte Sicht
 * auf die Scan-Resultate) — hier ausreichend, da nur Geräte-Anwesenheit/-Kennungen interessieren,
 * keine Positionsbestimmung über die Empfangsstärke.
 */
class BleTrackerScanner(private val context: Context) {

    data class Sighting(val address: String, val appleManufacturerData: ByteArray?)

    fun scan(windowMillis: Long = SCAN_WINDOW_MILLIS): List<Sighting> {
        ensurePermissionsGranted()
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "BLUETOOTH_SCAN nicht erteilt — kein Scan möglich")
            return emptyList()
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val scanner = adapter.bluetoothLeScanner ?: return emptyList()

        val results = ConcurrentHashMap<String, ScanResult>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                results[result.device.address] = result
            }
            override fun onBatchScanResults(batch: MutableList<ScanResult>) {
                batch.forEach { results[it.device.address] = it }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE-Scan fehlgeschlagen: Fehlercode $errorCode")
            }
        }

        try {
            scanner.startScan(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "BLE-Scan-Start fehlgeschlagen", e)
            return emptyList()
        }
        try {
            Thread.sleep(windowMillis)
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (e: SecurityException) {
                Log.w(TAG, "BLE-Scan-Stopp fehlgeschlagen", e)
            }
        }

        return results.values.map { result ->
            Sighting(result.device.address, result.scanRecord?.manufacturerSpecificData?.get(APPLE_MANUFACTURER_ID))
        }
    }

    /** Adressen bereits gekoppelter Geräte — ein wiederholt gesehenes eigenes Kopfhörer-/
     * Uhren-Gerät ist erwartungsgemäß dauerhaft in der Nähe, kein Verdachtsfall. */
    fun bondedAddresses(): Set<String> {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return emptySet()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptySet()
        return try {
            adapter.bondedDevices?.map { it.address }?.toSet().orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "Liste gekoppelter Geräte nicht lesbar", e)
            emptySet()
        }
    }

    private fun ensurePermissionsGranted() {
        try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val admin = ComponentName(context, WardenDeviceAdminReceiver::class.java)
            for (permission in REQUIRED_PERMISSIONS) {
                dpm.setPermissionGrantState(admin, context.packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Berechtigungs-Selbstfreigabe fehlgeschlagen", e)
        }
    }

    private companion object {
        const val TAG = "BleTrackerScanner"
        const val APPLE_MANUFACTURER_ID = 0x004C
        const val SCAN_WINDOW_MILLIS = 6_000L
        val REQUIRED_PERMISSIONS = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    }
}
