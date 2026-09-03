package de.ble1st.warden.config

import android.content.Context
import android.util.Log
import de.ble1st.warden.antitheft.AntiTheftAlarmStorage
import de.ble1st.warden.antitheft.AntiTheftLockStateReceiver
import de.ble1st.warden.autoreboot.AutoRebootStorage
import de.ble1st.warden.bus.ConcordBus
import de.ble1st.warden.cellsecurity.CellSecurityStorage
import de.ble1st.warden.clipboard.ClipboardGuardStorage
import de.ble1st.warden.domain.cellsecurity.CellSecurityReaction
import de.ble1st.warden.domain.config.WardenConfigSnapshot
import de.ble1st.warden.domain.profile.AutoProfileConfig
import de.ble1st.warden.domain.profile.WardenProfile
import de.ble1st.warden.domain.sim.SimChangeReaction
import de.ble1st.warden.domain.wifitrust.WifiTrustReaction
import de.ble1st.warden.failedattempts.FailedAttemptsRebootStorage
import de.ble1st.warden.profile.AutoProfileStorage
import de.ble1st.warden.registry.WardenOrganizationNameStorage
import de.ble1st.warden.registry.WardenSupportMessageStorage
import de.ble1st.warden.pin.WardenLockScreenTextStorage
import de.ble1st.warden.sim.SimChangeStorage
import de.ble1st.warden.wifitrust.WifiTrustStorage

/**
 * Schreibt einen [WardenConfigSnapshot] in die einzelnen lokalen Speicherorte zurück
 * (2026-09-03) — das Gegenstück zu [WardenConfigExporter].
 *
 * **Reihenfolge bei der Safeguard-Registry ist bewusst**: zuerst [snapshot.effectiveProfile] (falls
 * gesetzt) über [ConcordBus.applyProfile], **danach erst** [snapshot.safeguardActiveState] einzeln
 * über [ConcordBus.applySafeguard]/[ConcordBus.revertSafeguard] — ein Profil-Apply nimmt laut
 * [de.ble1st.warden.registry.WardenProfileApplier]-Klassendoc jeden nicht im Profil enthaltenen
 * Schalter zurück; in der umgekehrten Reihenfolge würde ein Profil-Apply die gerade erst
 * importierten Einzel-Anpassungen sofort wieder überschreiben. So entsteht exakt der Zustand, den
 * [WardenConfigExporter] beim Export vorgefunden hat: Profil-Basis plus die Abweichungen davon.
 *
 * Unbekannte Safeguard-IDs (Export aus einer anderen Warden-Version) werden übersprungen, nicht als
 * Fehler behandelt — dieselbe Verlustarm-über-Versionsgrenzen-Haltung wie
 * [de.ble1st.warden.domain.config.WardenConfigCodec]s Klassendoc.
 *
 * SIM-/Zellen-Baseline werden beim Import immer verworfen (unabhängig vom bisherigen Zustand),
 * dieselbe Begründung wie beim Einschalten der jeweiligen Reaktion in
 * [de.ble1st.warden.ui.WardenStatusActivity]: eine Baseline aus der Zeit vor dem Import gehört zu
 * einem möglicherweise ganz anderen Gerätezustand.
 */
class WardenConfigImporter(private val context: Context, private val concordBus: ConcordBus) {

    data class Result(val safeguardsApplied: Int, val safeguardsFailed: List<String>, val profileApplied: Boolean)

    fun import(snapshot: WardenConfigSnapshot): Result {
        var profileApplied = false
        snapshot.effectiveProfile?.let { name ->
            WardenProfile.entries.firstOrNull { it.name == name }?.let { profile ->
                runCatching { concordBus.applyProfile(profile) }
                    .onSuccess { profileApplied = true }
                    .onFailure { Log.w(TAG, "Import: Profil $name konnte nicht angewendet werden", it) }
            }
        }

        val knownIds = concordBus.listSafeguards().toSet()
        var applied = 0
        val failed = mutableListOf<String>()
        for ((id, active) in snapshot.safeguardActiveState) {
            if (id !in knownIds) continue
            val ok = runCatching {
                if (active) concordBus.applySafeguard(id) else concordBus.revertSafeguard(id)
            }.getOrDefault(false)
            if (ok) applied++ else failed += id
        }

        ClipboardGuardStorage.setEnabled(context, snapshot.clipboardGuardEnabled)
        ClipboardGuardStorage.setThresholdMillis(context, snapshot.clipboardGuardThresholdMillis)
        ClipboardGuardStorage.setCrossAppMonitoringEnabled(context, snapshot.clipboardCrossAppMonitoringEnabled)

        SimChangeStorage.clearBaseline(context)
        SimChangeStorage.saveReaction(context, snapshot.simChangeReaction?.let(::simReactionOrNull))
        CellSecurityStorage.clearObservation(context)
        CellSecurityStorage.saveReaction(context, snapshot.cellSecurityReaction?.let(::cellReactionOrNull))

        WifiTrustStorage.saveReaction(context, snapshot.wifiTrustReaction?.let(::wifiReactionOrNull))
        // Overlay statt Ersetzen: bereits vorhandene vertraute Netze bleiben erhalten, ein Import
        // ist eine Ergänzung, kein Zurücksetzen einer vom Nutzer seither weiter gepflegten Liste.
        snapshot.trustedWifiSsids.forEach { WifiTrustStorage.addTrustedSsid(context, it) }

        AntiTheftAlarmStorage.setMotionAlarmEnabled(context, snapshot.antiTheftMotionAlarmEnabled)
        AntiTheftAlarmStorage.setChargerAlarmEnabled(context, snapshot.antiTheftChargerAlarmEnabled)
        AntiTheftLockStateReceiver.syncRegistration(context)

        WardenLockScreenTextStorage.save(context, snapshot.lockScreenText)
        WardenOrganizationNameStorage.save(context, snapshot.organizationName)
        WardenSupportMessageStorage.save(context, snapshot.supportMessage)

        AutoRebootStorage.saveThresholdHours(context, snapshot.autoRebootThresholdHours)
        FailedAttemptsRebootStorage.saveThreshold(context, snapshot.failedAttemptsRebootThreshold)

        AutoProfileStorage.save(
            context,
            AutoProfileConfig(
                nightProfile = snapshot.autoProfileNightProfile?.let { name -> WardenProfile.entries.firstOrNull { it.name == name } },
                dayProfile = snapshot.autoProfileDayProfile?.let { name -> WardenProfile.entries.firstOrNull { it.name == name } },
                nightStartMinuteOfDay = snapshot.autoProfileNightStartMinuteOfDay ?: AutoProfileConfig.DEFAULT_NIGHT_START_MINUTE,
                nightEndMinuteOfDay = snapshot.autoProfileNightEndMinuteOfDay ?: AutoProfileConfig.DEFAULT_NIGHT_END_MINUTE,
                escalateOnCriticalThreat = snapshot.autoProfileEscalateOnCriticalThreat,
            ),
        )

        return Result(safeguardsApplied = applied, safeguardsFailed = failed, profileApplied = profileApplied)
    }

    private fun simReactionOrNull(name: String) = SimChangeReaction.entries.firstOrNull { it.name == name }
    private fun cellReactionOrNull(name: String) = CellSecurityReaction.entries.firstOrNull { it.name == name }
    private fun wifiReactionOrNull(name: String) = WifiTrustReaction.entries.firstOrNull { it.name == name }

    private companion object {
        const val TAG = "WardenConfigImporter"
    }
}
