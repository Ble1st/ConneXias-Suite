package de.ble1st.warden.netlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import uniffi.connexias_barbican.ProtectedSocketFactory
import uniffi.connexias_barbican.SocketException as BarbicanSocketException

/**
 * "Netz-Sperre" (2026-08-27): Warden-eigener `VpnService`, portiert vom ConneXias-Framework-
 * Quellprojekt (`barbican/vpn/BarbicanVpnService.kt`) — dort ein fremdes zweites APK, hier
 * Wardens eigener Prozess (kein Cross-APK-AIDL/Broadcast mehr nötig, s.
 * `CLAUDE.md`-"Netz-Sperre"-Abschnitt und [NetLockdownAuthorizer]-Klassendoc). Anders als das
 * Quellprojekt (reiner Sinkhole, Milestone I.1) läuft hier der echte NAT-/DNS-Filter-Tunnel
 * ([BarbicanEngine.startCapturedTunnel]) — s. `rust/barbican/src/engine.rs`-Moduldoc für die
 * bewusste IPv4-only-Scope-Reduktion dieser Umsetzungsrunde, deshalb hier nur eine IPv4-Adresse/
 * -Route am [Builder].
 *
 * **Single-Thread-`Executor` für start/stop/reload:** anders als im Quellprojekt (dortiger Main-
 * Thread-Deadlock-Fund kam von einem synchron blockierenden Cross-Prozess-Bind, das hier
 * strukturell entfällt — [NetworkFirewallPolicyStore] ist ein lokaler, verschlüsselter Datei-Read
 * im selben Prozess, kein IPC) bleibt die Auslagerung trotzdem sinnvoll: `Builder.establish()`
 * und der Envelope-Read sind Platten-I/O bzw. AES-GCM-Arbeit, die laut allgemeiner Android-Hygiene
 * nie auf dem Main-Thread laufen sollte (StrictMode-Disk-Read-Verstoß) — und serialisiert
 * start/stop/reload untereinander, damit kein Daten-Race auf [tunInterface] entsteht.
 */
class WardenVpnService : VpnService(), ProtectedSocketFactory {

    private var tunInterface: ParcelFileDescriptor? = null

    /** Serialisiert start/stop/reload — nur dieser Thread greift auf [tunInterface] zu. */
    private val worker = Executors.newSingleThreadExecutor()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Muss synchron/zeitnah auf dem Main-Thread passieren (Android-Anforderung an
        // startForegroundService()) — unabhängig davon, wie lange die eigentliche Tunnelarbeit auf
        // [worker] dauert.
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_STOP_TUNNEL -> {
                worker.execute { stopTunnel() }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_TUNNEL -> {
                worker.execute {
                    stopTunnel(releaseForeground = false)
                    startTunnel()
                }
                return START_STICKY
            }
        }
        worker.execute { startTunnel() }
        return START_STICKY
    }

    override fun onDestroy() {
        worker.execute { stopTunnel() }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onRevoke() {
        worker.execute { stopTunnel() }
        super.onRevoke()
    }

    /** Läuft ausschließlich auf [worker]. */
    private fun startTunnel() {
        if (tunInterface != null) return
        val policyStore = NetworkFirewallPolicyStore(NetworkFirewallPolicyStore.buildEnvelopeFile(this))
        val allowedPackages = policyStore.allowedPackageNames()
        Log.i(TAG, "Establishing TUN with ${allowedPackages.size} bypass app(s)")
        val builder = Builder()
            .setSession("Warden Netz-Sperre")
            .setMtu(MTU)
            .addAddress(TUNNEL_IPV4, 32)
            .addRoute("0.0.0.0", 0)
            // KRITISCH (2026-08-27 Live-Fund #2): der DNS-Server darf NICHT dieselbe Adresse wie
            // `addAddress` sein. Eine Adresse, die dem Interface selbst zugewiesen ist, wird vom
            // Kernel als "lokale Zustellung" behandelt — Pakete dorthin verlassen den lokalen
            // IP-Stack nie in Richtung `tun_fd`, sie werden intern zugestellt (und mangels eines
            // echten lauschenden Sockets auf `TUNNEL_IPV4:53` verworfen), bevor Wardens Lese-Thread
            // sie je zu sehen bekommt. Live auf dem Testgerät bestätigt: mit `addDnsServer
            // (TUNNEL_IPV4)` erreichten *keine* echten IPv4-Pakete (weder eigene Test-Queries noch
            // Chrome-Traffic) je die Engine — nur unabhängiges IPv6-Router-Solicitation-Rauschen
            // kam an (`v4=0, v6=N` in den Debug-Zählern). `TUNNEL_DNS_IPV4` ist deshalb eine eigene,
            // vom Interface selbst verschiedene Adresse im selben /24 — Pakete dorthin durchlaufen
            // den normalen Routing-Pfad (`0.0.0.0/0 -> tun0`) und landen tatsächlich im TUN-fd.
            // `engine.rs`s `set_any_ip(true)` akzeptiert ohnehin jede Ziel-IP, die konkrete
            // DNS-Server-Adresse ist der Rust-Seite gegenüber irrelevant.
            .addDnsServer(TUNNEL_DNS_IPV4)
            // KRITISCH (2026-08-27 Live-Fund): ohne setBlocking(true) liefert Android den TUN-fd
            // non-blocking aus. `engine.rs`s Lese-Thread nutzt eine simple blockierende
            // `file.read()`-Schleife (identisches Muster wie `sinkhole.rs`) und behandelt jeden
            // Fehler außer Interrupted als fatal (`Err(_) => break`) — ein sofortiges `WouldBlock`
            // beim allerersten Aufruf (noch kein Paket wartet) tötet den Lese-Thread lautlos, bevor
            // je ein Paket verarbeitet wird. `startCapturedTunnel()` meldet trotzdem `running=true`
            // (der Thread wurde ja erfolgreich gestartet, nur stirbt er sofort wieder) — das
            // Symptom (Tunnel/tun0 korrekt aufgebaut, aber komplett kein Traffic, nicht mal die
            // NXDOMAIN-Fastpath-Antwort für eine blockgelistete Domain) live auf dem Testgerät
            // gefunden, s. [[warden-netzsperre-feature-2026-08-27]].
            .setBlocking(true)
        for (pkg in allowedPackages) {
            runCatching { builder.addDisallowedApplication(pkg) }
                .onFailure { Log.w(TAG, "addDisallowedApplication fehlgeschlagen für $pkg", it) }
        }
        val pfd = builder.establish()
        if (pfd == null) {
            Log.e(TAG, "TUN establish() lieferte null")
            stopSelf()
            return
        }
        tunInterface = pfd
        try {
            val blocklistStore = DomainBlocklistStore(DomainBlocklistStore.buildEnvelopeFile(this))
            BarbicanEngine.setBlocklist(blocklistStore.effectiveBlocklist())
            Log.i(TAG, "Calling startCapturedTunnel(fd=${pfd.fd}, ipv4=$TUNNEL_IPV4)")
            BarbicanEngine.startCapturedTunnel(pfd.fd, TUNNEL_IPV4, this)
            Log.i(TAG, "startCapturedTunnel returned OK, running=${BarbicanEngine.isCapturedTunnelRunning()}")
        } catch (e: Exception) {
            Log.e(TAG, "Rust-Tunnel-Start fehlgeschlagen", e)
            stopTunnel()
            stopSelf()
        }
    }

    private fun stopTunnel(releaseForeground: Boolean = true) {
        runCatching { BarbicanEngine.stopCapturedTunnel() }
        tunInterface?.close()
        tunInterface = null
        if (releaseForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    // --- ProtectedSocketFactory: von Rust (`nat.rs`/`engine.rs`) über den UniFFI-Callback
    // aufgerufen, um für eine NAT-Session einen echten, `protect()`-geschützten Socket zu
    // beschaffen (verhindert eine Routing-Schleife zurück in den eigenen Tunnel — Standard-API
    // dafür). Ownership des zurückgegebenen fd geht an Rust über (s. `callback.rs`-Klassendoc):
    // `ParcelFileDescriptor.fromSocket(...)` dupliziert den Java-Socket-fd, `detachFd()` entkoppelt
    // die Java-Seite davon, und `socket.close()` schließt danach nur das ursprüngliche, jetzt
    // überflüssige Java-Duplikat — der an Rust zurückgegebene fd bleibt unberührt offen.

    override fun openTcp(dstIp: String, dstPort: UShort): Int {
        val socket = Socket()
        try {
            protect(socket)
            socket.connect(InetSocketAddress(dstIp, dstPort.toInt()), CONNECT_TIMEOUT_MS)
            val rawFd = ParcelFileDescriptor.fromSocket(socket).detachFd()
            socket.close()
            return rawFd
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw BarbicanSocketException.Failed()
        }
    }

    override fun openUdp(dstIp: String, dstPort: UShort): Int {
        val socket = DatagramSocket()
        try {
            protect(socket)
            socket.connect(InetSocketAddress(dstIp, dstPort.toInt()))
            val rawFd = ParcelFileDescriptor.fromDatagramSocket(socket).detachFd()
            socket.close()
            return rawFd
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw BarbicanSocketException.Failed()
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "warden_net_lockdown"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Warden Netz-Sperre", NotificationManager.IMPORTANCE_LOW),
        )
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("Netz-Sperre aktiv")
            .setContentText("Warden filtert und schützt den Netzwerkverkehr dieses Geräts")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "WardenVpn"
        private const val MTU = 1500
        private const val NOTIFICATION_ID = 42
        private const val CONNECT_TIMEOUT_MS = 10_000
        /** Tunnel-eigene IPv4-Adresse (`addAddress`) — dient als `any_ip`-Default-Route-Gateway
         * auf der Rust-Seite (s. `engine.rs`-Moduldoc). NICHT als DNS-Server-Adresse verwenden
         * (s. [TUNNEL_DNS_IPV4]-Doc — Live-Fund 2026-08-27). */
        const val TUNNEL_IPV4 = "10.64.0.1"
        /** DNS-Server-Adresse (`addDnsServer`) — bewusst eine andere Adresse als [TUNNEL_IPV4]
         * (s. Kommentar an der `addDnsServer`-Aufrufstelle). Jede DNS-Anfrage läuft so garantiert
         * durch den Tunnel/den Blocklisten-Filter, statt am System-Resolver vorbeizugehen — der
         * eigentliche Zweck des ursprünglichen (fehlerhaften) Kommentars hier, jetzt mit der
         * korrekten Adresse umgesetzt. */
        const val TUNNEL_DNS_IPV4 = "10.64.0.2"
        const val ACTION_STOP_TUNNEL = "de.ble1st.warden.netlock.action.STOP_TUNNEL"
        const val ACTION_RELOAD_TUNNEL = "de.ble1st.warden.netlock.action.RELOAD_TUNNEL"
    }
}
