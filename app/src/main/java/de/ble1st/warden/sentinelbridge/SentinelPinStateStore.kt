package de.ble1st.warden.sentinelbridge

import android.content.Context
import androidx.core.content.edit

/**
 * Wardens Gedächtnis für den zuletzt von Sentinel gemeldeten PIN-Zustand (Vorschlag U-8,
 * 2026-08-29).
 *
 * Hintergrund: Sentinel ist ein eigenes APK mit eigener UID und eigenem
 * credential-verschlüsseltem Speicher — Warden **kann** dessen PIN-Blob nicht lesen, weder direkt
 * noch über einen ContentProvider (den es bewusst nicht gibt). Bis hierher erfuhr Warden vom
 * Fehlen der Sentinel-PIN erst reaktiv über
 * [SentinelSignalReceiver.ACTION_ENGAGE_REFUSED], also genau im Ernstfall — dem teuersten
 * denkbaren Zeitpunkt für die Erkenntnis, dass der Kiosk wirkungslos ist. Sentinel meldet den
 * Zustand deshalb jetzt unaufgefordert bei jedem `onResume()`/`onPause()`.
 *
 * Fail-safe-Regel des Projekts, hier als Dreizustand statt eines Booleans: `null` heißt
 * **"Sentinel hat sich noch nie gemeldet"**, nicht "keine PIN". Der Unterschied ist bedeutsam —
 * eine frisch installierte Sentinel-App, die noch nie geöffnet wurde, hat tatsächlich keine PIN,
 * eine seit Monaten eingerichtete aber seither nicht geöffnete hat eine. Die UI zeigt darum
 * "unbekannt" statt zu raten.
 *
 * Bewusst SharedPreferences statt [de.ble1st.warden.crypto.EnvelopeFile]: ein einzelnes Bit
 * "Sentinel hat eine PIN", das ohnehin jeder Blick auf den Kiosk-Bildschirm verrät, rechtfertigt
 * keine Keystore-Runde bei jedem Broadcast.
 */
object SentinelPinStateStore {

    private const val PREFS_NAME = "warden_sentinel_pin_state"
    private const val KEY_PIN_CONFIGURED = "pin_configured"

    /** Von [SentinelSignalReceiver] beim Empfang von
     * `SentinelActivity.ACTION_PIN_STATE` aufgerufen. */
    fun record(context: Context, pinConfigured: Boolean) {
        prefs(context).edit { putBoolean(KEY_PIN_CONFIGURED, pinConfigured) }
    }

    /** `null` = Sentinel hat sich noch nie gemeldet (s. Klassendoc — *nicht* "keine PIN"). */
    fun pinConfigured(context: Context): Boolean? {
        val prefs = prefs(context)
        return if (prefs.contains(KEY_PIN_CONFIGURED)) prefs.getBoolean(KEY_PIN_CONFIGURED, false) else null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
