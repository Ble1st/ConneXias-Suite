package de.ble1st.warden.config

import android.content.Context
import de.ble1st.warden.antitheft.AntiTheftAlarmStorage
import de.ble1st.warden.autoreboot.AutoRebootStorage
import de.ble1st.warden.bus.ConcordBus
import de.ble1st.warden.cellsecurity.CellSecurityStorage
import de.ble1st.warden.clipboard.ClipboardGuardStorage
import de.ble1st.warden.domain.config.WardenConfigSnapshot
import de.ble1st.warden.failedattempts.FailedAttemptsRebootStorage
import de.ble1st.warden.profile.AutoProfileStorage
import de.ble1st.warden.registry.WardenOrganizationNameStorage
import de.ble1st.warden.registry.WardenSupportMessageStorage
import de.ble1st.warden.pin.WardenLockScreenTextStorage
import de.ble1st.warden.sim.SimChangeStorage
import de.ble1st.warden.wifitrust.WifiTrustStorage

/**
 * Liest [WardenConfigSnapshot] aus den einzelnen lokalen Speicherorten zusammen (2026-09-03) —
 * reiner Lesevorgang, keine Mutation. Läuft über [ConcordBus] nur dort, wo bereits ein
 * `BusCommand.READ`-Zugriffspunkt existiert ([ConcordBus.listSafeguards]/[ConcordBus
 * .safeguardStates]); alles andere sind reine lokale Präferenzen ohne eigenen Bus-Zugriffspunkt
 * (dieselbe Unterscheidung wie überall sonst im Projekt — s. [ConcordBus]s Klassendoc), direkt aus
 * den jeweiligen `*Storage`-Objekten gelesen.
 *
 * Ausdrücklich **nicht** exportiert (s. [WardenConfigSnapshot]-Klassendoc): PIN-Blob, Krypto-
 * Schlüsselmaterial, Audit-/Security-Event-Log, jeder Sentinel-seitige Zustand — sowie, innerhalb
 * der Safeguard-Registry, alles, was nicht über [de.ble1st.warden.registry.SafeguardCatalog
 * .reversible] läuft (Lockdown-Bündel, Master-Switch-Zustand).
 */
class WardenConfigExporter(private val context: Context, private val concordBus: ConcordBus) {

    fun export(): WardenConfigSnapshot {
        val safeguardIds = concordBus.listSafeguards()
        val safeguardStates = concordBus.safeguardStates(safeguardIds)
            .mapNotNull { (id, active) -> active?.let { id to it } }
            .toMap()
        val autoProfileConfig = AutoProfileStorage.load(context)
        val antiTheft = AntiTheftAlarmStorage.load(context)

        return WardenConfigSnapshot(
            safeguardActiveState = safeguardStates,
            effectiveProfile = AutoProfileStorage.loadLastEffective(context)?.name,
            clipboardGuardEnabled = ClipboardGuardStorage.isEnabled(context),
            clipboardGuardThresholdMillis = ClipboardGuardStorage.thresholdMillis(context),
            clipboardCrossAppMonitoringEnabled = ClipboardGuardStorage.isCrossAppMonitoringEnabled(context),
            simChangeReaction = SimChangeStorage.loadReaction(context)?.name,
            cellSecurityReaction = CellSecurityStorage.loadReaction(context)?.name,
            wifiTrustReaction = WifiTrustStorage.loadReaction(context)?.name,
            trustedWifiSsids = WifiTrustStorage.loadTrustedSsids(context),
            antiTheftMotionAlarmEnabled = antiTheft.motionAlarmEnabled,
            antiTheftChargerAlarmEnabled = antiTheft.chargerAlarmEnabled,
            lockScreenText = WardenLockScreenTextStorage.load(context),
            organizationName = WardenOrganizationNameStorage.load(context),
            supportMessage = WardenSupportMessageStorage.load(context),
            autoRebootThresholdHours = AutoRebootStorage.loadThresholdHours(context),
            failedAttemptsRebootThreshold = FailedAttemptsRebootStorage.loadThreshold(context),
            autoProfileNightProfile = autoProfileConfig.nightProfile?.name,
            autoProfileDayProfile = autoProfileConfig.dayProfile?.name,
            autoProfileNightStartMinuteOfDay = autoProfileConfig.nightStartMinuteOfDay,
            autoProfileNightEndMinuteOfDay = autoProfileConfig.nightEndMinuteOfDay,
            autoProfileEscalateOnCriticalThreat = autoProfileConfig.escalateOnCriticalThreat,
        )
    }
}
