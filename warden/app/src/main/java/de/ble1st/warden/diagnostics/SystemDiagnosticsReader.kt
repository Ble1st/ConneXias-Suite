package de.ble1st.warden.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import de.ble1st.warden.antitheft.AntiTheftAlarmStorage
import de.ble1st.warden.antitheft.AntiTheftLockStateReceiver
import de.ble1st.warden.appmanagement.SuspiciousAppScanWorker
import de.ble1st.warden.autoreboot.AutoRebootWorker
import de.ble1st.warden.cellsecurity.CellSecurityWorker
import de.ble1st.warden.performance.BatterySamplingWorker
import de.ble1st.warden.profile.AutoProfileWorker
import de.ble1st.warden.score.ScoreReminderWorker
import de.ble1st.warden.sim.SimChangeWorker
import de.ble1st.warden.tracker.BleTrackerWorker
import de.ble1st.warden.usb.UsbAutoLockWorker
import de.ble1st.warden.wifitrust.WifiTrustWorker

/** Ein einzelner periodischer `WorkManager`-Job und ob er gerade eingeplant ist. */
data class WorkerDiagnostic(val label: String, val scheduled: Boolean)

/** Eine selbst gewährte gefährliche Berechtigung und ihr aktueller Freigabestatus. */
data class PermissionDiagnostic(val label: String, val permission: String, val granted: Boolean)

/** Der dynamisch (de-)registrierte Diebstahlschutz-Empfänger — s. eigene Doc unten. */
data class ReceiverDiagnostic(val label: String, val registered: Boolean, val featureEnabled: Boolean)

data class SystemDiagnosticsSnapshot(
    val workers: List<WorkerDiagnostic>,
    val permissions: List<PermissionDiagnostic>,
    val antiTheftReceiver: ReceiverDiagnostic,
)

/**
 * Ideenliste-Vorschlag 5 ("Systemdiagnose-Bildschirm", 2026-09-03) — rein lesend, kein neuer
 * Scan-Mechanismus: bündelt Zustand, der bislang nur verstreut und indirekt prüfbar war (ist ein
 * periodischer Worker wirklich eingeplant? hat Warden die selbst gewährte Berechtigung, die ein
 * lokaler Auslöser braucht, tatsächlich noch? ist der einzige dynamisch registrierte Empfänger im
 * Projekt gerade aktiv?). Beantwortet nicht "funktioniert der Auslöser", nur "sind seine
 * strukturellen Voraussetzungen erfüllt" — z. B. sagt ein eingeplanter `CellSecurityWorker` nichts
 * darüber, ob `CellSecurityController.checkAndMaybeReact` intern wegen einer deaktivierten Reaktion
 * sofort zurückkehrt (dasselbe "immer eingeplant, Controller prüft selbst intern"-Muster wie überall
 * sonst in diesem Projekt).
 *
 * Die Worker-Namen kommen bewusst als öffentliche `UNIQUE_WORK_NAME`-Konstante aus jedem Worker
 * selbst (vor diesem Feature `private`) statt hier als eigene Zeichenketten-Kopie — sonst entstünde
 * genau die Art von zwei-Wahrheiten-Quelle, die dieses Projekt an anderer Stelle wiederholt als
 * echten Bug gefunden hat (s. `CLAUDE.md`, "Zwei Soll-Zustände für Always-On-VPN").
 */
class SystemDiagnosticsReader(private val context: Context) {

    fun read(): SystemDiagnosticsSnapshot {
        val workManager = WorkManager.getInstance(context)
        val workers = WORKER_NAMES.map { (label, uniqueWorkName) ->
            val scheduled = runCatching {
                workManager.getWorkInfosForUniqueWork(uniqueWorkName).get().any {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                }
            }.getOrDefault(false)
            WorkerDiagnostic(label, scheduled)
        }

        val permissions = PERMISSIONS.map { (label, permission) ->
            PermissionDiagnostic(
                label = label,
                permission = permission,
                granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
            )
        }

        val antiTheftReceiver = ReceiverDiagnostic(
            label = "Diebstahlschutz-Empfänger",
            registered = AntiTheftLockStateReceiver.isRegistered(),
            featureEnabled = runCatching { AntiTheftAlarmStorage.load(context).isAnyEnabled }.getOrDefault(false),
        )

        return SystemDiagnosticsSnapshot(workers, permissions, antiTheftReceiver)
    }

    private companion object {
        val WORKER_NAMES = listOf(
            "Verdächtige-Apps-Scan" to SuspiciousAppScanWorker.UNIQUE_WORK_NAME,
            "Auto-Reboot" to AutoRebootWorker.UNIQUE_WORK_NAME,
            "Akku-Verlauf" to BatterySamplingWorker.UNIQUE_WORK_NAME,
            "SIM-Wechsel-Prüfung" to SimChangeWorker.UNIQUE_WORK_NAME,
            "Auto-Profil" to AutoProfileWorker.UNIQUE_WORK_NAME,
            "USB-Auto-Sperre" to UsbAutoLockWorker.UNIQUE_WORK_NAME,
            "Mobilfunk-Sicherheit" to CellSecurityWorker.UNIQUE_WORK_NAME,
            "Score-Erinnerung" to ScoreReminderWorker.UNIQUE_WORK_NAME,
            "WLAN-Vertrauensliste" to WifiTrustWorker.UNIQUE_WORK_NAME,
            "BLE-Tracker-Wächter" to BleTrackerWorker.UNIQUE_WORK_NAME,
        )

        val PERMISSIONS = listOf(
            "Telefonstatus (SIM-Wechsel)" to Manifest.permission.READ_PHONE_STATE,
            "Standort (Mobilfunk-/WLAN-/Diebstahlschutz-Auslöser)" to Manifest.permission.ACCESS_FINE_LOCATION,
            "Bluetooth-Scan (BLE-Tracker)" to Manifest.permission.BLUETOOTH_SCAN,
            "Bluetooth-Verbindung (BLE-Tracker)" to Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}
