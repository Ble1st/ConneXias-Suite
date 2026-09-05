package de.ble1st.warden.diagnostics

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import de.ble1st.warden.antitheft.AntiTheftAlarmStorage
import de.ble1st.warden.antitheft.AntiTheftLockStateReceiver
import de.ble1st.warden.appmanagement.SuspiciousAppScanWorker
import de.ble1st.warden.autoreboot.AutoRebootWorker
import de.ble1st.warden.cellsecurity.CellSecurityWorker
import de.ble1st.warden.domain.policycoexistence.PolicyConflictRecord
import de.ble1st.warden.domain.policycoexistence.PolicyCoexistenceDecision
import de.ble1st.warden.performance.BatterySamplingWorker
import de.ble1st.warden.policycoexistence.PolicyConflictStore
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

/**
 * Ein zweiter Geräteadmin neben Warden. `deviceOwner` = dieses Paket hält zusätzlich die
 * Device-Owner-Rolle; bei allen anderen ist es ein bloß aktivierter Admin. Beides ist erst einmal
 * normal (Herstelleraufsätze bringen regelmäßig einen mit) — erst zusammen mit einem echten
 * Konflikt aus [AdminCoexistenceDiagnostic.policyProblems] wird daraus ein Befund.
 */
data class ActiveAdminDiagnostic(val label: String, val componentName: String, val deviceOwner: Boolean)

/** Tier 3 der DPC-Recherche (2026-09-05) — s. [PolicyCoexistenceDecision]. */
data class AdminCoexistenceDiagnostic(
    val wardenIsDeviceOwner: Boolean,
    val otherActiveAdmins: List<ActiveAdminDiagnostic>,
    val policyProblems: List<PolicyConflictRecord>,
    /** `false` = es kam noch nie eine Rückmeldung. Eine leere [policyProblems]-Liste ist dann
     * *keine* Entwarnung, s. [PolicyCoexistenceDecision.hasEverReported]. */
    val policyFeedbackReceived: Boolean,
)

/**
 * Systemseitiger Diebstahlschutz (Android 15+: Diebstahlerkennungssperre, Offline-Gerätesperre,
 * Remote-Sperre), Tier 3 der DPC-Recherche (2026-09-05).
 *
 * **Es gibt bewusst kein `enabled`-Feld.** Für keine der drei Funktionen existiert eine
 * öffentliche Lese-API — weder ein `PackageManager.FEATURE_*`, noch ein dokumentierter
 * `Settings`-Schlüssel, noch etwas im `DevicePolicyManager` (gegen `android-37.0/android.jar`
 * nachgeprüft). Ein geratener Zustand wäre hier schlimmer als gar keiner: der Nutzer würde einen
 * Schutz für aktiv halten, den Warden nie gelesen hat. Angezeigt wird deshalb nur, was wirklich
 * lesbar ist — [deviceSecure], die gemeinsame Voraussetzung aller drei Funktionen — plus ein
 * Verweis in die Systemeinstellungen, wo sie tatsächlich stehen. Genau das meinte die Vorgabe
 * "verweisen statt nachbauen": Warden dupliziert diese Funktionen nicht, es macht sie auffindbar.
 */
data class TheftProtectionDiagnostic(val deviceSecure: Boolean)

data class SystemDiagnosticsSnapshot(
    val workers: List<WorkerDiagnostic>,
    val permissions: List<PermissionDiagnostic>,
    val antiTheftReceiver: ReceiverDiagnostic,
    val adminCoexistence: AdminCoexistenceDiagnostic,
    val theftProtection: TheftProtectionDiagnostic,
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

        return SystemDiagnosticsSnapshot(
            workers = workers,
            permissions = permissions,
            antiTheftReceiver = antiTheftReceiver,
            adminCoexistence = readAdminCoexistence(),
            theftProtection = readTheftProtection(),
        )
    }

    private fun readAdminCoexistence(): AdminCoexistenceDiagnostic {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val ownPackage = context.packageName
        val others = runCatching {
            dpm?.activeAdmins.orEmpty()
                .filter { it.packageName != ownPackage }
                .map { admin ->
                    ActiveAdminDiagnostic(
                        label = readApplicationLabel(admin),
                        componentName = admin.flattenToShortString(),
                        deviceOwner = runCatching { dpm?.isDeviceOwnerApp(admin.packageName) == true }.getOrDefault(false),
                    )
                }
        }.getOrDefault(emptyList())

        val records = runCatching { PolicyConflictStore.load(context) }.getOrDefault(emptyList())
        return AdminCoexistenceDiagnostic(
            wardenIsDeviceOwner = runCatching { dpm?.isDeviceOwnerApp(ownPackage) == true }.getOrDefault(false),
            otherActiveAdmins = others,
            policyProblems = PolicyCoexistenceDecision.currentProblems(records),
            policyFeedbackReceived = PolicyCoexistenceDecision.hasEverReported(records),
        )
    }

    /** Fällt auf den Paketnamen zurück statt auf einen Platzhalter: ein Admin, dessen Label sich
     * nicht lesen lässt, ist genau der, den man in der Liste wiederfinden können muss. */
    private fun readApplicationLabel(admin: ComponentName): String = runCatching {
        val info = context.packageManager.getApplicationInfo(admin.packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(admin.packageName)

    private fun readTheftProtection(): TheftProtectionDiagnostic = TheftProtectionDiagnostic(
        deviceSecure = runCatching {
            context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        }.getOrDefault(false),
    )

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
