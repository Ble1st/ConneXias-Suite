package de.ble1st.warden.hardening

import android.content.Context
import androidx.core.content.edit
import de.ble1st.warden.domain.hardening.FailedAttemptsWipeThreshold
import de.ble1st.warden.domain.hardening.LocationEnforcement
import de.ble1st.warden.domain.hardening.TimeIntegrityMode

/**
 * Soll-Zustand der drei Tier-2-Auswahlmenüs (2026-09-05) in einem gemeinsamen Store.
 *
 * **Warum ein gemeinsamer statt drei einzelner Stores:** die drei Einstellungen teilen sich
 * Lebensdauer, Bildschirm und Anwendungszeitpunkt ([HardeningPreferencesController.applyAll] wird
 * für alle drei zusammen aufgerufen). Drei getrennte `SharedPreferences`-Dateien für je einen
 * Enum-Wert wären reine Zeremonie — anders als etwa bei
 * [de.ble1st.warden.pin.WardenLockdownArmPendingEngageStore] vs.
 * [de.ble1st.warden.pin.WardenLockTaskPendingEngageStore], die getrennt sein *müssen*, weil beide
 * unabhängig voneinander ausstehen können.
 *
 * Klartext-`SharedPreferences` mit derselben Begründung wie
 * [de.ble1st.warden.pin.LockdownTriggerProfileStore]: reine Verhaltenseinstellungen, kein
 * Geheimnis, und ein Zurückfallen auf die Defaults (alle drei "aus") ist kein Sicherheitsverlust —
 * es ist der Zustand vor Einführung dieser Funktionen.
 *
 * **Device-Protected Storage**, aus einem konkreten Grund und nicht aus Gewohnheit:
 * [de.ble1st.warden.boot.RegistryReconciliationReceiver] zieht diese drei Werte nach einem
 * Neustart nach, und der Receiver feuert bei `ACTION_LOCKED_BOOT_COMPLETED` — also **vor** der
 * ersten Entsperrung. Im credential-verschlüsselten Bereich wäre die Datei dort nicht lesbar, die
 * Werte kämen als Defaults zurück, und die Reconciliation würde eine gesetzte Richtlinie
 * ausgerechnet im BFU-Fenster zurücknehmen. Dieselbe Überlegung wie bei
 * [de.ble1st.warden.pin.WardenLockScreenTextStorage], das aus demselben Grund dort liegt.
 */
object HardeningPreferencesStorage {
    private const val PREFS_NAME = "warden_hardening_preferences"
    private const val KEY_LOCATION = "location_enforcement"
    private const val KEY_TIME = "time_integrity"
    private const val KEY_WIPE = "failed_attempts_wipe"

    fun loadLocation(context: Context): LocationEnforcement =
        prefs(context).getString(KEY_LOCATION, null)
            ?.let { stored -> runCatching { LocationEnforcement.valueOf(stored) }.getOrNull() }
            ?: LocationEnforcement.DEFAULT

    fun saveLocation(context: Context, value: LocationEnforcement) {
        prefs(context).edit { putString(KEY_LOCATION, value.name) }
    }

    fun loadTimeIntegrity(context: Context): TimeIntegrityMode =
        prefs(context).getString(KEY_TIME, null)
            ?.let { stored -> runCatching { TimeIntegrityMode.valueOf(stored) }.getOrNull() }
            ?: TimeIntegrityMode.DEFAULT

    fun saveTimeIntegrity(context: Context, value: TimeIntegrityMode) {
        prefs(context).edit { putString(KEY_TIME, value.name) }
    }

    fun loadWipeThreshold(context: Context): FailedAttemptsWipeThreshold =
        prefs(context).getString(KEY_WIPE, null)
            ?.let { stored -> runCatching { FailedAttemptsWipeThreshold.valueOf(stored) }.getOrNull() }
            ?: FailedAttemptsWipeThreshold.DEFAULT

    fun saveWipeThreshold(context: Context, value: FailedAttemptsWipeThreshold) {
        prefs(context).edit { putString(KEY_WIPE, value.name) }
    }

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
