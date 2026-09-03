package de.ble1st.files.data.localshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import de.ble1st.files.R
import java.io.File
import java.util.UUID

/**
 * Foreground-Service für die WLAN/Hotspot-Ordnerfreigabe (`LocalHttpServer`). Nutzer-getriggert
 * und kurzlebig (Start/Stop über einen einzelnen Bildschirm-Umschalter) — anders als Wardens
 * Always-On-VPN-Service betrifft ihn die dortige `dataSync`-Kontingent-Problematik für
 * selbst-neustartende Dauer-Services nicht (s. Warden-Debugging-Historie), `dataSync` passt hier
 * unverändert: der Server verschiebt/kopiert im Hintergrund Dateiinhalte über das Netzwerk, exakt
 * der Anwendungsfall, für den dieser Foreground-Service-Typ gedacht ist.
 *
 * Läuft nur, solange die Notification sichtbar ist (`STOP_FOREGROUND_REMOVE` erst bei explizitem
 * Stop) — ein Wechsel in den Hintergrund darf einen laufenden Download für einen anderen Nutzer im
 * selben WLAN nicht abbrechen.
 */
class LocalShareService : Service() {

    private var server: LocalHttpServer? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_local_share),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServerAndSelf()
            return START_NOT_STICKY
        }
        val dirPath = intent?.getStringExtra(EXTRA_DIRECTORY)
        if (dirPath == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val directory = File(dirPath)
        val token = UUID.randomUUID().toString().replace("-", "")
        val newServer = LocalHttpServer(directory, token)
        val result = runCatching { newServer.start() }
        if (result.isFailure) {
            LocalShareState.publish(LocalShareStatus.Failed(result.exceptionOrNull()?.message ?: "Server konnte nicht gestartet werden"))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        server = newServer
        val ip = LocalIpAddress.get()
        if (ip == null) {
            newServer.stop()
            server = null
            LocalShareState.publish(LocalShareStatus.Failed(getString(R.string.local_share_error_no_network)))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val url = "http://$ip:${newServer.port}/?token=$token"
        startForeground(NOTIFICATION_ID, buildNotification(directory))
        LocalShareState.publish(LocalShareStatus.Running(directory, url))
        // Kein START_STICKY: ein vom System nach einem Kill neu gestarteter Service bekäme kein
        // Intent mehr (kein Ordnerpfad, kein Token) und könnte den Server ohnehin nicht sinnvoll
        // fortsetzen — derselbe "kein automatischer Wiederanlauf für einen nutzer-getriggerten
        // Vorgang"-Ansatz wie FileOperationService.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        server = null
        LocalShareState.publish(LocalShareStatus.Stopped)
    }

    private fun stopServerAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(directory: File): Notification {
        val stopIntent = Intent(this, LocalShareService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.notification_title_local_share))
            .setContentText(directory.name.ifEmpty { directory.path })
            .setOngoing(true)
            .addAction(0, getString(R.string.local_share_action_stop), stopPendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "local_share"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "de.ble1st.files.action.STOP_LOCAL_SHARE"
        private const val EXTRA_DIRECTORY = "directory"

        fun start(context: Context, directory: File) {
            val intent = Intent(context, LocalShareService::class.java).putExtra(EXTRA_DIRECTORY, directory.path)
            context.startForegroundService(intent)
        }

        /** `startForegroundService` statt `startService`, obwohl der Stop-Zweig in
         * [onStartCommand] `startForeground` nie erneut aufruft — der Service ist zu diesem
         * Zeitpunkt bereits im Vordergrund (aus [start]), die "muss `startForeground` binnen
         * ~5s aufrufen"-Frist von `startForegroundService` gilt pro Service-Instanz, nicht pro
         * Aufruf, und ist hier also schon erfüllt. */
        fun stop(context: Context) {
            context.startForegroundService(Intent(context, LocalShareService::class.java).setAction(ACTION_STOP))
        }
    }
}
