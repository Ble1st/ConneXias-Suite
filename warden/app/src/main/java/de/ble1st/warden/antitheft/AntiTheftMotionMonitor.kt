package de.ble1st.warden.antitheft

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import de.ble1st.warden.domain.antitheft.AntiTheftAlarmDecision
import kotlin.math.sqrt

/**
 * Beschleunigungsmesser-Überwachung für den Bewegungsalarm (2026-09-03, Ideenliste Punkt 3) —
 * registriert/entfernt sich als [SensorEventListener] über [start]/[stop], angestoßen von
 * [AntiTheftLockStateReceiver] bei Bildschirm-aus/-an statt dauerhaft zu laufen (unnötiger
 * Akkuverbrauch bei entsperrtem Gerät, wo dieses Feature ohnehin nicht auslösen darf, s.
 * [AntiTheftAlarmDecision]-Klassendoc).
 *
 * Nutzt `Sensor.TYPE_LINEAR_ACCELERATION` statt des rohen `TYPE_ACCELEROMETER` — Android liefert
 * diesen Wert bereits um die Erdbeschleunigung bereinigt, ein einzelner Schwellenwertvergleich
 * reicht deshalb ohne eigene Baseline/Tiefpassfilterung. **Nicht auf jedem Gerät verfügbar** (ist
 * ein aus anderen Sensoren abgeleiteter, kein zwingend vorhandener Hardware-Sensor) — fehlt er,
 * bleibt [start] wirkungslos und protokolliert eine Warnung; es gibt bewusst keinen Fallback auf den
 * rohen Beschleunigungssensor (dessen Schwerkraftanteil müsste dann selbst herausgerechnet werden,
 * ein zusätzlicher Kalibrierungsschritt, der die ohnehin schon unverifizierte Heuristik nur
 * fehleranfälliger machen würde).
 *
 * `THRESHOLD_MS2` ist **nicht gegen reale Diebstahlszenarien kalibriert** (s.
 * [AntiTheftAlarmDecision]-Klassendoc) — eine grobe Setzung, die echtes Aufnehmen/Wegreißen von
 * bloßem Liegenlassen unterscheiden soll, ohne Feldtest auf echter Hardware.
 */
object AntiTheftMotionMonitor {
    private const val TAG = "AntiTheftMotionMonitor"
    private const val THRESHOLD_MS2 = 4.0f

    @Volatile
    private var listener: SensorEventListener? = null

    fun start(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (listener != null) return
            val sensorManager = appContext.getSystemService(SensorManager::class.java)
            val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            if (sensorManager == null || sensor == null) {
                Log.w(TAG, "TYPE_LINEAR_ACCELERATION nicht verfügbar — Bewegungsalarm inaktiv")
                return
            }
            val newListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val magnitude = magnitude(event.values[0], event.values[1], event.values[2])
                    val config = AntiTheftAlarmStorage.load(appContext)
                    val isLocked = isKeyguardLocked(appContext)
                    if (AntiTheftAlarmDecision.shouldTriggerOnMotion(config, isLocked, magnitude, THRESHOLD_MS2)) {
                        AntiTheftAlarmController(appContext).trigger("Ungewöhnliche Bewegung erkannt")
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(newListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            listener = newListener
        }
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            val current = listener ?: return
            val sensorManager = appContext.getSystemService(SensorManager::class.java)
            sensorManager?.unregisterListener(current)
            listener = null
        }
    }

    private fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    private fun isKeyguardLocked(context: Context): Boolean =
        context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
}
