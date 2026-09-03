package de.ble1st.warden.antitheft

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import de.ble1st.warden.wardenAuditLog

/**
 * Ausführung des Diebstahlschutz-Alarms (2026-09-03) — laut, bewusst so einfach wie möglich
 * gehalten, damit sich möglichst wenig daran verstecken lässt, das schiefgehen kann.
 *
 * **Der Alarm endet ausschließlich durch eine echte Geräte-Entsperrung** —
 * [AntiTheftLockStateReceiver] ruft [stopAlarm] nur bei `ACTION_USER_PRESENT` auf, nicht schon bei
 * `ACTION_SCREEN_ON`. Keine Notification-Stopp-Aktion (s. [AntiTheftAlarmNotifier]-Klassendoc), kein
 * Zeit-Timeout: ein sich selbst abschaltender Alarm wäre für einen Dieb, der das Gerät nur kurz vom
 * Körper fernhält, wirkungslos.
 *
 * **`AudioAttributes.USAGE_ALARM` statt der Klingelton-/Medien-Lautstärke** — derselbe Stream, den
 * Android für Weckalarme nutzt, läuft typischerweise auch bei aktiviertem "Nicht stören" und
 * stummgeschaltetem Klingelton weiter. Bewusst **kein** `AudioManager.setStreamVolume(STREAM_ALARM,
 * ...)`: das würde die System-Alarmlautstärke dauerhaft verändern, ein invasiver Nebeneffekt, der
 * über diesen einen Alarm hinaus wirkt. Bekannte Grenze: ist die Alarmlautstärke des Geräts selbst
 * niedrig eingestellt, ist auch dieser Alarm entsprechend leise — nicht überschrieben.
 *
 * Ton und Vibration laufen als statischer, gemeinsamer Zustand über alle Controller-Instanzen
 * hinweg (dieselbe Instanz wird nie zweimal für denselben Alarm verwendet — [trigger] wird pro
 * Ereignis frisch aus [AntiTheftLockStateReceiver]/[AntiTheftMotionMonitor] erzeugt).
 */
class AntiTheftAlarmController(private val context: Context) {

    fun trigger(reason: String) {
        stopAlarm()
        wardenAuditLog(context).append(Log.ERROR, TAG, "Diebstahlschutz-Alarm ausgelöst: $reason")
        runCatching { AntiTheftAlarmNotifier(context).notify(reason) }
            .onFailure { Log.w(TAG, "Alarm-Benachrichtigung fehlgeschlagen", it) }
        startSound()
        startVibration()
    }

    fun stopAlarm() {
        synchronized(LOCK) {
            activePlayer?.let {
                try {
                    it.stop()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "MediaPlayer.stop() fehlgeschlagen", e)
                }
                it.release()
            }
            activePlayer = null
        }
        try {
            vibratorManager()?.defaultVibrator?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Vibration-Stopp fehlgeschlagen", e)
        }
        runCatching { AntiTheftAlarmNotifier(context).cancel() }
            .onFailure { Log.w(TAG, "Alarm-Benachrichtigung konnte nicht zurückgenommen werden", it) }
    }

    private fun startSound() {
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: return
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            player.setDataSource(context, uri)
            player.isLooping = true
            player.prepare()
            player.start()
            synchronized(LOCK) { activePlayer = player }
        } catch (e: Exception) {
            Log.w(TAG, "Alarmton konnte nicht gestartet werden", e)
        }
    }

    private fun startVibration() {
        try {
            val vibrator = vibratorManager()?.defaultVibrator ?: return
            // repeat=0 -> die Sequenz beginnt nach jedem Durchlauf wieder am Index 0, endlose
            // Wiederholung bis zu einem expliziten cancel().
            vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN_MS, 0))
        } catch (e: Exception) {
            Log.w(TAG, "Vibration konnte nicht gestartet werden", e)
        }
    }

    private fun vibratorManager(): VibratorManager? =
        context.getSystemService(VibratorManager::class.java)

    private companion object {
        const val TAG = "AntiTheftAlarm"
        val VIBRATION_PATTERN_MS = longArrayOf(0, 500, 300)
        private val LOCK = Any()

        @Volatile
        private var activePlayer: MediaPlayer? = null
    }
}
