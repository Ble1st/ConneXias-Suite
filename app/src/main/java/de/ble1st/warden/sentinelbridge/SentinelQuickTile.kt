package de.ble1st.warden.sentinelbridge

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.TileService
import de.ble1st.warden.appmanagement.SentinelInstallStatus
import de.ble1st.warden.appmanagement.SentinelInstallStatusReader
import de.ble1st.warden.domain.pin.LockdownTriggerProfile
import de.ble1st.warden.domain.presence.SensitiveAction
import de.ble1st.warden.pin.LockdownTriggerProfileStore
import de.ble1st.warden.pin.WardenLockTaskDrillStorage
import de.ble1st.warden.pin.WardenLockTaskPendingEngageStore
import de.ble1st.warden.presence.SensitiveActionActivity
import de.ble1st.warden.ui.WardenStatusActivity

/**
 * "Lockdown-Auslöse-Profil" (2026-08-27) — Quick-Settings-Kachel als zweiter Schnell-Einstieg für
 * `SensitiveAction.LOCKDOWN_TASK_ENGAGE`, neben dem Dashboard-Button "Kiosk jetzt" in
 * `de.ble1st.warden.ui.WardenStatusActivity`. Verhalten hängt vom aktiven
 * [LockdownTriggerProfile] ab (`LockdownTriggerProfileStore`):
 *
 * - [LockdownTriggerProfile.STRICT]: feuert nie direkt — die Kachel degradiert zu einem reinen
 *   Shortcut in `SensitiveActionActivity` (vorausgewählt, inkl. dortiger
 *   Presence-/Kühlzeit-Verschärfung).
 * - [LockdownTriggerProfile.STANDARD]/[LockdownTriggerProfile.FAST]: gehen über den bereits
 *   vorhandenen, bereits geprüften [WardenLockTaskPendingEngageStore]-Umweg —
 *   `TileService.onClick()` hat keinen zuverlässigen `Activity`-Kontext für Presence/Dialog,
 *   exakt dasselbe Problem wie der Bedrohungs-Scan-Auto-Engage-Pfad
 *   (`de.ble1st.warden.appmanagement.SuspiciousAppScanController`), der denselben Store bereits
 *   nutzt. `de.ble1st.warden.ui.WardenStatusActivity.performPendingLockTaskEngage()` prüft dort
 *   `DestructiveCommandGuard` exakt wie beim Dashboard-Button — kein neuer, ungeschützter Pfad.
 *
 * `onStartListening()`/dynamischer Titel/Subtitle bewusst nicht in v1 — reiner
 * Schnellauslöser-Zweck rechtfertigt keine zusätzliche Live-Binding-Logik.
 */
class SentinelQuickTile : TileService() {

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            when (LockdownTriggerProfileStore.load(applicationContext)) {
                LockdownTriggerProfile.STRICT -> openSensitiveActionPreselected()
                LockdownTriggerProfile.STANDARD -> requestOrFallback(requiresConfirmation = true)
                LockdownTriggerProfile.FAST -> requestOrFallback(requiresConfirmation = false)
            }
        }
    }

    private fun requestOrFallback(requiresConfirmation: Boolean) {
        val installed = SentinelInstallStatusReader(applicationContext).currentStatus() is SentinelInstallStatus.Installed
        val drillConfirmed = WardenLockTaskDrillStorage.isConfirmed(applicationContext)
        if (installed && drillConfirmed) {
            WardenLockTaskPendingEngageStore.requestEngage(
                applicationContext,
                reason = "Schnellzugriff (Quick-Settings-Kachel)",
                requiresConfirmation = requiresConfirmation,
            )
        }
        // Weder installiert noch Drill bestätigt: bewusst KEINE Anforderung vormerken — Wardens
        // eigene UI (Safeguards ▸ App-Lock) erklärt den fehlenden Zustand besser als ein stiller
        // Fehlschlag beim nächsten Öffnen.
        openActivity(Intent(applicationContext, WardenStatusActivity::class.java))
    }

    private fun openSensitiveActionPreselected() {
        openActivity(
            Intent(applicationContext, SensitiveActionActivity::class.java)
                .putExtra(SensitiveActionActivity.EXTRA_PRESELECTED_ACTION, SensitiveAction.LOCKDOWN_TASK_ENGAGE.name),
        )
    }

    private fun openActivity(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pendingIntent)
    }

    private companion object {
        const val REQUEST_CODE = 1
    }
}
