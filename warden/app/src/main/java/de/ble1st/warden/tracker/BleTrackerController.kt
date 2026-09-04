package de.ble1st.warden.tracker

import android.content.Context
import android.util.Log
import de.ble1st.warden.domain.tracker.AirTagLikeAdvertisementDecision
import de.ble1st.warden.domain.tracker.BleTrackerPersistenceDecision
import de.ble1st.warden.wardenAuditLog

/**
 * Android-Glue für ein einzelnes BLE-Tracker-Prüfintervall (2026-09-03, Ideenliste
 * "BLE-Tracker-Wächter, auch AirTags") — angestoßen periodisch von [BleTrackerWorker]. Läuft nur,
 * wenn [TrackerGuardStorage.isEnabled].
 *
 * Zwei kombinierte Signale, s. auch [AirTagLikeAdvertisementDecision]/
 * [BleTrackerPersistenceDecision]-Klassendocs für die jeweilige Begründung: ein Find-My-artiges
 * Werbepaket senkt die für eine Meldung nötige Sichtungsschwelle ([MIN_SIGHTINGS_FINDMY] statt
 * [MIN_SIGHTINGS_GENERIC]) — ein solches Paket ist von sich aus schon ein stärkerer Hinweis auf ein
 * zweckgebautes Tracking-Zubehör als ein x-beliebiges wiederholt gesehenes BLE-Gerät (das genauso
 * gut der Kopfhörer der Nachbarwohnung sein kann).
 */
class BleTrackerController(private val context: Context) {

    fun checkOnce() {
        if (!TrackerGuardStorage.isEnabled(context)) return
        val scanner = BleTrackerScanner(context)
        val bonded = scanner.bondedAddresses()
        val sightings = scanner.scan()
        if (sightings.isEmpty()) return

        val history = BleTrackerHistoryStore(context)
        val now = System.currentTimeMillis()
        val logStore = wardenAuditLog(context)

        for (sighting in sightings) {
            if (sighting.address in bonded) continue
            val findMyShaped = AirTagLikeAdvertisementDecision.isFindMyShaped(APPLE_MANUFACTURER_ID, sighting.appleManufacturerData)
            val entry = history.recordSighting(sighting.address, now, findMyShaped)
            if (entry.notified) continue

            val minSightings = if (entry.findMyShaped) MIN_SIGHTINGS_FINDMY else MIN_SIGHTINGS_GENERIC
            val suspicious = BleTrackerPersistenceDecision.isSuspicious(
                sightingCount = entry.sightingCount,
                firstSeenMillis = entry.firstSeenMillis,
                lastSeenMillis = entry.lastSeenMillis,
                minSightings = minSightings,
                minSpanMillis = MIN_SPAN_MILLIS,
            )
            if (!suspicious) continue

            history.markNotified(sighting.address)
            logStore.append(
                Log.WARN,
                TAG,
                "Möglicherweise mitlaufendes BLE-Gerät: ${entry.address} " +
                    "(${entry.sightingCount} Sichtungen" + (if (entry.findMyShaped) ", Find-My-artig)" else ")"),
            )
            runCatching { BleTrackerNotifier(context).send(entry.address, entry.findMyShaped, entry.sightingCount) }
                .onFailure { Log.w(TAG, "BLE-Tracker-Benachrichtigung fehlgeschlagen", it) }
        }
    }

    private companion object {
        const val TAG = "BleTracker"
        const val APPLE_MANUFACTURER_ID = 0x004C
        const val MIN_SIGHTINGS_GENERIC = 4
        const val MIN_SIGHTINGS_FINDMY = 2
        const val MIN_SPAN_MILLIS = 10 * 60 * 1000L // 10 Minuten
    }
}
