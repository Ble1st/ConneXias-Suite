package de.ble1st.warden.bus

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/**
 * Concord-Bus-CLIENT für den `:barbican`-Prozess (2026-08-31, Design-Dok
 * `docs/design-barbican-prozess-childvpn.md`) — Gegenstück zu [ConcordBusService], dem Bus-HOST im
 * Hauptprozess. Anders als [de.ble1st.warden.sentinelbridge.SentinelDeathWatchdog] (fremde APK,
 * `<uses-permission>`, Signature-Prüfung) bindet dies innerhalb DERSELBEN App an einen anderen
 * Prozess — kein Manifest-`<uses-permission>`, keine Signature-Prüfung nötig, gleiche UID.
 *
 * Bewusst nur ein schmaler Zweck: [reportEvent] spiegelt
 * [de.ble1st.warden.vpn.WardenVpnService]/`BarbicanEngine`-Ereignisse (Tunnel-Status, ein
 * unerwartet gestorbener Engine-Thread) in Wardens Hash-Chain-Audit-Log — direkter Zugriff auf
 * [de.ble1st.warden.logging.HashChainLogStore] aus diesem Prozess wäre ein zweiter, nicht
 * synchronisierter Schreiber auf dieselbe Envelope-Datei (kein File-Locking dokumentiert).
 *
 * **Verbindungsaufbau ist asynchron, [reportEvent] blockiert nie darauf:** ein vor Verbindungsstand
 * gemeldetes Ereignis landet in [pending] (FIFO, auf [MAX_PENDING] begrenzt — ältestes Ereignis
 * fällt sonst raus statt unbegrenzt zu wachsen) und wird bei `onServiceConnected` nachgereicht.
 * Ein fehlgeschlagener `bindService()`-Aufruf oder eine abgelehnte `reportBarbicanEvent`-Autorisierung
 * (Rate-Limit) werden nur laut geloggt (Logcat) — ein fehlender Audit-Log-Eintrag ist ärgerlich,
 * darf aber nie den eigentlichen Tunnelbetrieb blockieren oder gar abbrechen.
 */
class BarbicanConcordClient(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private var connection: ServiceConnection? = null
    private var bus: IConcordBus? = null
    private val pending = ArrayDeque<Pair<Int, String>>()

    /** Bindet einmalig, idempotent — mehrfaches Aufrufen (z. B. bei jedem `startTunnel()`) baut
     * keine zweite Verbindung auf. */
    fun connect() {
        synchronized(lock) {
            if (connection != null) return
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val remote = service?.let { IConcordBus.Stub.asInterface(it) } ?: return
                    synchronized(lock) {
                        bus = remote
                        flushPendingLocked(remote)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    synchronized(lock) { bus = null }
                }
            }
            val intent = Intent(appContext, ConcordBusService::class.java)
            val bound = try {
                appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                Log.w(TAG, "bindService(ConcordBusService) fehlgeschlagen: $e")
                false
            }
            if (bound) {
                connection = conn
            } else {
                Log.w(TAG, "bindService(ConcordBusService) lieferte false — keine Ereignis-Meldung möglich")
            }
        }
    }

    fun disconnect() {
        synchronized(lock) {
            connection?.let { runCatching { appContext.unbindService(it) } }
            connection = null
            bus = null
        }
    }

    /** [priority] folgt android.util.Log-Konstanten (Log.INFO/WARN/ERROR). */
    fun reportEvent(priority: Int, message: String) {
        val remote: IConcordBus?
        synchronized(lock) {
            remote = bus
            if (remote == null) {
                if (pending.size >= MAX_PENDING) pending.removeFirst()
                pending.addLast(priority to message)
            }
        }
        remote?.let { send(it, priority, message) }
    }

    /** Läuft unter [lock] — [remote] ist frisch verbunden, es kann in dem Moment noch niemand
     * anderes lesen/schreiben. */
    private fun flushPendingLocked(remote: IConcordBus) {
        while (pending.isNotEmpty()) {
            val (priority, message) = pending.removeFirst()
            send(remote, priority, message)
        }
    }

    private fun send(remote: IConcordBus, priority: Int, message: String) {
        try {
            remote.reportBarbicanEvent(priority, message)
        } catch (e: Exception) {
            Log.w(TAG, "reportBarbicanEvent fehlgeschlagen: $e")
        }
    }

    private companion object {
        const val TAG = "BarbicanConcordClient"
        const val MAX_PENDING = 20
    }
}
