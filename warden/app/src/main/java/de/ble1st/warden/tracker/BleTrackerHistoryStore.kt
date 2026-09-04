package de.ble1st.warden.tracker

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Beobachtungs-Historie je gesehener (nicht gekoppelter) BLE-Adresse für [BleTrackerController]
 * (2026-09-03) — Klartext-`SharedPreferences`-JSON, dasselbe Muster wie
 * [de.ble1st.warden.score.SecurityScoreHistoryStore]/[de.ble1st.warden.performance
 * .BatteryHistoryStore]: eine gesehene BLE-Adresse ist kein schützenswertes Geheimnis, ein
 * zurückgesetzter Verlauf höchstens ärgerlich.
 *
 * Einträge, die seit [PRUNE_AFTER_MILLIS] nicht mehr gesehen wurden, werden bei jedem [record]
 * verworfen — ein Gerät, das seit zwei Tagen nicht mehr in Reichweite war, "folgt" nicht mehr,
 * ein späteres erneutes Auftauchen soll wieder bei Zählung 1 beginnen, nicht an einer längst
 * überholten Historie anknüpfen.
 */
class BleTrackerHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Entry(
        val address: String,
        val firstSeenMillis: Long,
        val lastSeenMillis: Long,
        val sightingCount: Int,
        val findMyShaped: Boolean,
        val notified: Boolean,
    )

    /** Aktualisiert (oder legt neu an) den Eintrag für [address] mit `nowMillis` als neuestem
     * Sichtungszeitpunkt und liefert den aktualisierten Eintrag zurück. */
    fun recordSighting(address: String, nowMillis: Long, findMyShaped: Boolean): Entry {
        val pruned = pruneStale(allEntries(), nowMillis)
        val existing = pruned[address]
        val updated = if (existing == null) {
            Entry(address, nowMillis, nowMillis, 1, findMyShaped, notified = false)
        } else {
            existing.copy(
                lastSeenMillis = nowMillis,
                sightingCount = existing.sightingCount + 1,
                findMyShaped = existing.findMyShaped || findMyShaped,
            )
        }
        saveAll(pruned + (address to updated))
        return updated
    }

    fun markNotified(address: String) {
        val all = allEntries()
        val entry = all[address] ?: return
        saveAll(all + (address to entry.copy(notified = true)))
    }

    private fun pruneStale(entries: Map<String, Entry>, nowMillis: Long): Map<String, Entry> =
        entries.filterValues { nowMillis - it.lastSeenMillis < PRUNE_AFTER_MILLIS }

    private fun allEntries(): Map<String, Entry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).associate { i ->
                val row = array.getJSONArray(i)
                val address = row.getString(0)
                address to Entry(
                    address = address,
                    firstSeenMillis = row.getLong(1),
                    lastSeenMillis = row.getLong(2),
                    sightingCount = row.getInt(3),
                    findMyShaped = row.getBoolean(4),
                    notified = row.getBoolean(5),
                )
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveAll(entries: Map<String, Entry>) {
        val array = JSONArray()
        // Verteidigung gegen eine entartete Anzahl gleichzeitig sichtbarer Geräte (dichtes
        // BLE-Umfeld) — dieselbe reine Schutzgrenze wie SecurityScoreHistoryStore.MAX_ENTRIES,
        // kein normaler Begrenzer.
        for (entry in entries.values.sortedByDescending { it.lastSeenMillis }.take(MAX_ENTRIES)) {
            val row = JSONArray()
            row.put(entry.address)
            row.put(entry.firstSeenMillis)
            row.put(entry.lastSeenMillis)
            row.put(entry.sightingCount)
            row.put(entry.findMyShaped)
            row.put(entry.notified)
            array.put(row)
        }
        prefs.edit { putString(KEY_ENTRIES, array.toString()) }
    }

    private companion object {
        const val PREFS_NAME = "warden_ble_tracker_history"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 200
        val PRUNE_AFTER_MILLIS = TimeUnit.HOURS.toMillis(48)
    }
}
