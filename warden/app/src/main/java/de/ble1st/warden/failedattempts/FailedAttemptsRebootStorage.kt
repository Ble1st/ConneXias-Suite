package de.ble1st.warden.failedattempts

import android.content.Context
import androidx.core.content.edit
import de.ble1st.warden.domain.failedattempts.FailedAttemptsRebootDecision

/**
 * Persistenz für [FailedAttemptsRebootController] — Soll-Schwelle und der laufende Zähler
 * aufeinanderfolgender Fehlversuche am System-Sperrbildschirm.
 *
 * **Device-Protected Storage, anders als [de.ble1st.warden.autoreboot.AutoRebootStorage]:**
 * `onPasswordFailed` wird genau dann zugestellt, wenn jemand am Sperrbildschirm scheitert — also
 * regelmäßig, bevor das Gerät nach einem Neustart je entsperrt wurde. Credential-verschlüsselte
 * `SharedPreferences` wären in diesem Moment nicht lesbar, der Zähler bliebe still auf 0 stehen
 * und die Funktion liefe genau im wichtigsten Fall (Gerät gestohlen, neu gestartet, Angreifer
 * probiert PINs) ins Leere. Dieselbe Begründung wie bei
 * [de.ble1st.warden.pin.WardenPinStorage]/[de.ble1st.warden.registry.RegistryStorage].
 *
 * Bewusst **keine** Envelope-Verschlüsselung (anders als PIN-Blob/Registry): hier liegen nur eine
 * Zahl aus den Einstellungen und ein Zähler — nichts, dessen Vertraulichkeit einen
 * Keystore-Round-Trip in einem Broadcast-Callback rechtfertigt, der pro Fehlversuch feuert.
 */
object FailedAttemptsRebootStorage {
    private const val PREFS_NAME = "warden_failed_attempts_reboot"
    private const val KEY_THRESHOLD = "threshold"
    private const val KEY_FAILED_ATTEMPTS = "failed_attempts"

    /** Anzahl Fehlversuche bis zum Neustart, `null` = deaktiviert. */
    fun loadThreshold(context: Context): Int? =
        prefs(context).getInt(KEY_THRESHOLD, 0).takeIf { it > 0 }

    fun saveThreshold(context: Context, threshold: Int?) {
        val normalized = threshold?.coerceIn(
            FailedAttemptsRebootDecision.MIN_THRESHOLD,
            FailedAttemptsRebootDecision.MAX_THRESHOLD,
        )
        prefs(context).edit {
            if (normalized == null) remove(KEY_THRESHOLD) else putInt(KEY_THRESHOLD, normalized)
        }
    }

    fun loadFailedAttempts(context: Context): Int = prefs(context).getInt(KEY_FAILED_ATTEMPTS, 0)

    fun saveFailedAttempts(context: Context, attempts: Int) {
        prefs(context).edit { putInt(KEY_FAILED_ATTEMPTS, attempts.coerceAtLeast(0)) }
    }

    fun resetFailedAttempts(context: Context) {
        prefs(context).edit { remove(KEY_FAILED_ATTEMPTS) }
    }

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
