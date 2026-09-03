package de.ble1st.warden.antitheft

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import de.ble1st.warden.domain.antitheft.AntiTheftAlarmDecision

/**
 * Dynamischer Empfänger für Bildschirm-/Ladekabel-Ereignisse (2026-09-03) — dasselbe Muster wie
 * [de.ble1st.warden.usb.UsbLockStateReceiver]: implizite Broadcasts wie `ACTION_SCREEN_OFF`/
 * `ACTION_POWER_DISCONNECTED` lassen sich nicht im Manifest registrieren, also
 * registriert/deregistriert [syncRegistration] diesen Empfänger je nachdem, ob irgendein
 * Diebstahlschutz-Auslöser aktiv ist.
 *
 * `ACTION_SCREEN_OFF` startet [AntiTheftMotionMonitor] (nur falls der Bewegungsalarm eingeschaltet
 * ist — ein permanent laufender Sensor-Listener bei ausgeschaltetem Bewegungsalarm wäre unnötiger
 * Akkuverbrauch). `ACTION_USER_PRESENT` (echte Entsperrung, nicht schon `ACTION_SCREEN_ON`) stoppt
 * sowohl den Monitor als auch einen laufenden Alarm — s. [AntiTheftAlarmController]-Klassendoc für
 * die Begründung, warum genau dieser und kein anderer Zeitpunkt den Alarm beendet.
 * `ACTION_POWER_DISCONNECTED` prüft direkt gegen [AntiTheftAlarmDecision.shouldTriggerOnChargerDisconnect].
 */
class AntiTheftLockStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        when (intent?.action) {
            Intent.ACTION_SCREEN_OFF -> {
                if (AntiTheftAlarmStorage.load(appContext).motionAlarmEnabled) {
                    AntiTheftMotionMonitor.start(appContext)
                }
            }
            Intent.ACTION_USER_PRESENT -> {
                AntiTheftMotionMonitor.stop(appContext)
                AntiTheftAlarmController(appContext).stopAlarm()
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                val config = AntiTheftAlarmStorage.load(appContext)
                val isLocked = appContext.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
                if (AntiTheftAlarmDecision.shouldTriggerOnChargerDisconnect(config, isLocked)) {
                    AntiTheftAlarmController(appContext).trigger("Ladekabel im gesperrten Zustand abgezogen")
                }
            }
            else -> return
        }
    }

    companion object {
        private const val TAG = "AntiTheftLockStateReceiver"

        @Volatile
        private var registered: AntiTheftLockStateReceiver? = null

        fun syncRegistration(context: Context) {
            val app = context.applicationContext
            val want = AntiTheftAlarmStorage.load(app).isAnyEnabled
            synchronized(this) {
                val current = registered
                if (want && current == null) {
                    val receiver = AntiTheftLockStateReceiver()
                    val filter = IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(Intent.ACTION_USER_PRESENT)
                        addAction(Intent.ACTION_POWER_DISCONNECTED)
                    }
                    app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    registered = receiver
                } else if (!want && current != null) {
                    try {
                        app.unregisterReceiver(current)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Diebstahlschutz-Receiver war nicht registriert", e)
                    }
                    registered = null
                    // Ausschalten reißt auch einen gerade laufenden Monitor/Alarm mit — sonst liefe
                    // ein bereits gestarteter Bewegungsalarm nach dem Abschalten des Features weiter,
                    // bis zur nächsten echten Entsperrung.
                    AntiTheftMotionMonitor.stop(app)
                    AntiTheftAlarmController(app).stopAlarm()
                }
            }
        }
    }
}
