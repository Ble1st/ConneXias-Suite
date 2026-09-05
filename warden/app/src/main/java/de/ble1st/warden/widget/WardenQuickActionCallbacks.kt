package de.ble1st.warden.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import de.ble1st.warden.appmanagement.SentinelInstallStatus
import de.ble1st.warden.appmanagement.SentinelInstallStatusReader
import de.ble1st.warden.domain.pin.LockdownTriggerProfilePolicy
import de.ble1st.warden.pin.LockdownTriggerProfileStore
import de.ble1st.warden.pin.WardenLockTaskDrillStorage
import de.ble1st.warden.pin.WardenLockTaskPendingEngageStore
import de.ble1st.warden.pin.WardenLockdownArmPendingEngageStore
import de.ble1st.warden.ui.WardenStatusActivity

/**
 * "Quick-Action-Widget für Lockdown/Sentinel-Kiosk" (2026-09-05, Nutzerwunsch) — die beiden
 * [ActionCallback]s hinter den zusätzlichen Widget-Schaltflächen in [WardenStatusWidget], sichtbar
 * nur wenn [WidgetQuickActionsStore.isEnabled] zutrifft.
 *
 * **Warum `actionRunCallback`, nicht `actionStartActivity` mit einem Intent-Extra:**
 * `WardenStatusActivity` ist als Launcher-Activity `exported="true"` — jede fremde App könnte ihr
 * ein Intent mit demselben Extra schicken. Ein `ActionCallback` läuft dagegen im eigenen
 * Prozess (dieselbe Glance-Infrastruktur wie ein `CoroutineWorker`, kein Cross-App-Aufruf), schreibt
 * die Anforderung also selbst in den jeweiligen Pending-Store, **bevor** überhaupt eine `Activity`
 * gestartet wird — exakt dasselbe Muster wie
 * [de.ble1st.warden.sentinelbridge.SentinelQuickTile.requestOrFallback] (auch dort schreibt
 * `TileService.onClick()`, das ebenfalls im eigenen Prozess läuft, zuerst den Store, bevor es eine
 * bloße "App öffnen"-`PendingIntent` baut). Die anschließend gestartete `WardenStatusActivity`
 * trägt **kein** aktionsspezifisches Extra — ihr regulärer `onResume()`-Pfad
 * (`consumePendingLockTaskEngage`/`consumePendingLockdownArm`) holt die Anforderung ab, exakt wie
 * bei jedem anderen Aufrufer dieser Stores. Ein Fremd-Intent an `WardenStatusActivity` kann diesen
 * Callback dadurch strukturell nicht auslösen — er hat keinen Weg, ihn überhaupt zu erreichen.
 *
 * **Der Master-Schalter wird hier, nicht nur beim Zeichnen des Widgets, erneut geprüft** —
 * derselbe "Aktion prüft ihre eigene Berechtigung frisch, verlässt sich nie auf einen
 * UI-Zustand von vorhin"-Grundsatz wie überall sonst im Projekt: zwischen dem letzten
 * Widget-Update (bis zu 30 Minuten alt) und einem Tap kann der Schalter längst wieder aus sein.
 */
class LockdownArmQuickActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (WidgetQuickActionsStore.isEnabled(context)) {
            val profile = LockdownTriggerProfileStore.load(context)
            if (LockdownTriggerProfilePolicy.quickTriggerEntryPointsEnabled(profile)) {
                WardenLockdownArmPendingEngageStore.requestEngage(
                    context,
                    reason = REASON,
                    requiresConfirmation = LockdownTriggerProfilePolicy.requiresConfirmationDialog(profile),
                )
            }
        }
        openApp(context)
    }

    private companion object {
        const val REASON = "Schnellzugriff (Widget)"
    }
}

/** Dieselbe Installiert-/Drill-Vorprüfung wie
 * [de.ble1st.warden.sentinelbridge.SentinelQuickTile.requestOrFallback] — ohne installiertes
 * Sentinel oder unbestätigten Notruf-Drill wird bewusst **keine** Anforderung vorgemerkt, Wardens
 * eigene UI erklärt den fehlenden Zustand besser als ein stiller Fehlschlag beim nächsten Öffnen. */
class KioskEngageQuickActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (WidgetQuickActionsStore.isEnabled(context)) {
            val profile = LockdownTriggerProfileStore.load(context)
            if (LockdownTriggerProfilePolicy.quickTriggerEntryPointsEnabled(profile)) {
                val installed = SentinelInstallStatusReader(context).currentStatus() is SentinelInstallStatus.Installed
                val drillConfirmed = WardenLockTaskDrillStorage.isConfirmed(context)
                if (installed && drillConfirmed) {
                    WardenLockTaskPendingEngageStore.requestEngage(
                        context,
                        reason = REASON,
                        requiresConfirmation = LockdownTriggerProfilePolicy.requiresConfirmationDialog(profile),
                    )
                }
            }
        }
        openApp(context)
    }

    private companion object {
        const val REASON = "Schnellzugriff (Widget)"
    }
}

private fun openApp(context: Context) {
    context.startActivity(
        Intent(context, WardenStatusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
