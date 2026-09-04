package de.ble1st.warden.autoreboot

import android.content.Context
import androidx.core.content.edit

/**
 * Persistiert Soll-Zeitfenster und Beobachtungs-Baseline für [AutoRebootController]. Anders als
 * [de.ble1st.warden.pin.WardenLockScreenTextStorage] & Co. bewusst **keine**
 * Device-Protected-Storage: die Funktion beobachtet den Sperrzustand periodisch über WorkManager,
 * das vor dem ersten Entsperren nach einem Boot ohnehin nicht zuverlässig läuft — es gibt also
 * nichts, das schon vor dem Entsperren reconciled werden müsste (anders als z. B. die
 * Kamerasperre, die `system_server`-seitig unabhängig vom Warden-Prozess durchsetzt wird).
 * Normale (credential-verschlüsselte) `SharedPreferences` reichen.
 */
object AutoRebootStorage {
    private const val PREFS_NAME = "warden_auto_reboot"
    private const val KEY_THRESHOLD_HOURS = "threshold_hours"
    private const val KEY_LAST_SEEN_UNLOCKED_MILLIS = "last_seen_unlocked_millis"

    /** Großzügige Obergrenze — verhindert Fehleingaben wie "999999" ohne echten Härtungs-Mehrwert. */
    const val MAX_THRESHOLD_HOURS = 168 // eine Woche

    /** Stunden bis zum Auto-Reboot, `null` = deaktiviert. */
    fun loadThresholdHours(context: Context): Int? {
        val stored = prefs(context).getInt(KEY_THRESHOLD_HOURS, 0)
        return stored.takeIf { it > 0 }
    }

    fun saveThresholdHours(context: Context, hours: Int?) {
        val normalized = hours?.coerceIn(1, MAX_THRESHOLD_HOURS)
        prefs(context).edit {
            if (normalized == null) remove(KEY_THRESHOLD_HOURS) else putInt(KEY_THRESHOLD_HOURS, normalized)
        }
    }

    /** `null` = seit Aktivierung dieser Funktion (oder App-Neuinstallation) noch nie beobachtet. */
    fun loadLastSeenUnlockedMillis(context: Context): Long? {
        val stored = prefs(context).getLong(KEY_LAST_SEEN_UNLOCKED_MILLIS, -1L)
        return stored.takeIf { it >= 0L }
    }

    fun saveLastSeenUnlockedMillis(context: Context, millis: Long) {
        prefs(context).edit { putLong(KEY_LAST_SEEN_UNLOCKED_MILLIS, millis) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
