// ⏸ PAUSIERT (2026-08-27): "Netz-Sperre" ist vorübergehend deaktiviert — Live-Test auf dem
// physischen Testgerät fand nach mehreren echten Bugfixes (siehe Commit 7252396 und
// warden-netzsperre-feature-2026-08-27-Memo) einen weiterhin ungeklärten Kernfehler: die
// DNS-Blockliste/NAT-Relay verarbeitet auf einem frisch aufgebauten Tunnel keinen Traffic mehr,
// Ursache unbekannt. Diese Datei liegt deshalb bewusst außerhalb jedes Gradle-Source-Sets
// (app/netlock-disabled/ statt app/src/main/java/) — wird NICHT mitkompiliert, ist nirgendwo
// verkabelt. Zum Reaktivieren: Verzeichnis zurück nach app/src/main/java/... verschieben, alle
// Wiederverkabelungsstellen aus dem Deaktivierungs-Commit rückgängig machen (siehe dessen
// Commit-Message für die vollständige Liste), Kernfehler zuerst klären.

package de.ble1st.warden.netlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    /** s. [openTcp]/[openUdp]-Klassendoc: alle `protect()`-Socket-Operationen müssen auf einem
     * regulären, von der Android-Runtime verwalteten Thread laufen. */
    private val socketOpsExecutor = Executors.newCachedThreadPool()

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
        socketOpsExecutor.shutdown()
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
            BarbicanEngine.startCapturedTunnel(pfd.fd, TUNNEL_IPV4, TUNNEL_DNS_IPV4, UPSTREAM_DNS_IPV4, this)
            Log.i(TAG, "startCapturedTunnel returned OK, running=${BarbicanEngine.isCapturedTunnelRunning()}")
        } catch (e: Exception) {
            Log.e(TAG, "Rust-Tunnel-Start fehlgeschlagen", e)
            stopTunnel()
            stopSelf()
        }
    }

    // KRITISCH (2026-08-27, Bug-3-Folge-Fund): `stopCapturedTunnel()` blockiert synchron, bis
    // Rusts Lese-Thread beendet ist (`EngineHandle::stop()` joint ihn) — der Lese-Thread selbst
    // wacht aber laut eigenem SAFETY-Kommentar in `engine.rs` erst auf, wenn der `tun_fd`
    // GESCHLOSSEN wird. Die vorherige Reihenfolge (erst `stopCapturedTunnel()`, dann
    // `tunInterface?.close()`) deadlockte deshalb GARANTIERT auf [worker] — die schließende Zeile
    // wurde nie erreicht, weil die Zeile davor bereits ewig auf genau dieses Schließen wartete.
    // Live gefunden: nach dem ersten `ACTION_STOP_TUNNEL`/`ACTION_RELOAD_TUNNEL` reagierte
    // [worker] (ein `newSingleThreadExecutor`) auf KEINEN weiteren Aufruf mehr, obwohl `tun0`
    // (mit einem bereits toten Lese-Thread, also ohne jeglichen Traffic) fälschlich weiter als
    // aktiv erschien. Fix: den fd zuerst schließen — das entsperrt den Lese-Thread sofort, danach
    // kehrt `stopCapturedTunnel()`s Join tatsächlich zurück.
    private fun stopTunnel(releaseForeground: Boolean = true) {
        tunInterface?.close()
        tunInterface = null
        runCatching { BarbicanEngine.stopCapturedTunnel() }
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

    // KRITISCH (2026-08-27, Bug-3-Folge-Fund): zwei voneinander unabhängige Gründe, warum
    // `protect()`/Socket-Aufbau hier NICHT direkt auf dem aufrufenden Thread laufen darf, und
    // warum `java.net.Socket()`/`DatagramSocket()` durch `SocketChannel`/`DatagramChannel` ersetzt
    // sind:
    // (1) Der aufrufende Thread ist einer von Rusts eigenen, per `std::thread::spawn` erzeugten
    //     OS-Threads (`spawn_tcp_connect`/`spawn_udp_connect`) — via UniFFI/JNA an die JVM
    //     angehängt (der Kotlin-Aufruf selbst funktioniert, s. `ProtectedSocketFactory`-Klassendoc),
    //     aber KEIN von der Android-Runtime selbst erzeugter Thread. Live bestätigt: `protect()`
    //     hing auf einem solchen Thread dauerhaft fest (nie ein `protect()`-Return über Minuten,
    //     mehrere parallel aufgestaute Aufrufe) — auf einem regulären `ExecutorService`-Thread
    //     ([socketOpsExecutor]) schlägt exakt derselbe Aufruf sofort (mit einer echten, fangbaren
    //     Exception) fehl oder gelingt, hängt aber nie.
    // (2) `java.net.Socket()`/`DatagramSocket()`s No-Arg-Konstruktoren legen ihren nativen fd nicht
    //     zuverlässig sofort an — `protect()` braucht aber einen bereits existierenden fd, um ihn
    //     von der eigenen Tunnel-Lockdown-Ausnahme auszunehmen. `SocketChannel.open()`/
    //     `DatagramChannel.open()` legen ihren fd dagegen garantiert sofort an — der offizielle, in
    //     Androids eigenen VpnService-Beispielen (ToyVpn) verwendete Weg für genau diesen Zweck.
    //
    // Das eigentliche `EPERM` beim Socket-Aufbau selbst (unabhängig von (1)/(2)) kam von einer
    // dritten, unabhängigen Ursache: der fehlenden `android.permission.INTERNET`-Deklaration im
    // Manifest (s. dortiger Kommentar) — Warden brauchte vor Netz-Sperre nie eine Netzwerk-
    // Permission. Alle drei Fixes zusammen (Thread, Channel-Typ, Manifest-Permission) waren nötig,
    // keiner allein hätte gereicht.
    override fun openTcp(dstIp: String, dstPort: UShort): Int = openProtected(dstIp, dstPort) { ip, port ->
        val channel = SocketChannel.open()
        try {
            protect(channel.socket())
            channel.socket().connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
            val rawFd = ParcelFileDescriptor.fromSocket(channel.socket()).detachFd()
            channel.close()
            rawFd
        } catch (e: Exception) {
            runCatching { channel.close() }
            throw e
        }
    }

    override fun openUdp(dstIp: String, dstPort: UShort): Int = openProtected(dstIp, dstPort) { ip, port ->
        val channel = DatagramChannel.open()
        try {
            protect(channel.socket())
            channel.connect(InetSocketAddress(ip, port))
            val rawFd = ParcelFileDescriptor.fromDatagramSocket(channel.socket()).detachFd()
            channel.close()
            rawFd
        } catch (e: Exception) {
            runCatching { channel.close() }
            throw e
        }
    }

    /** Führt [body] auf [socketOpsExecutor] aus und blockiert den aufrufenden (Rust-eigenen)
     * Thread auf das Ergebnis — s. Klassendoc-Kommentar an [openTcp]/[openUdp]. Ein Timeout statt
     * eines unbegrenzten `get()` ist ein zusätzliches Sicherheitsnetz, kein primärer Fix: sollte
     * [body] selbst jemals wieder hängen, bleibt wenigstens dieser eine Flow fehlschlagend statt
     * den aufrufenden Rust-Thread (und damit einen Platz in [PENDING_TCP_FDS]/[PENDING_UDP_FDS])
     * dauerhaft zu blockieren. */
    private fun openProtected(dstIp: String, dstPort: UShort, body: (String, Int) -> Int): Int =
        try {
            socketOpsExecutor.submit(Callable { body(dstIp, dstPort.toInt()) })
                .get(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "openTcp/openUdp($dstIp:$dstPort) fehlgeschlagen", e)
            throw BarbicanSocketException.Failed()
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
        /** Echter Upstream-DNS-Resolver (2026-08-27, Bug-3-Folge-Fix), an den `engine.rs` jede
         * Anfrage an [TUNNEL_DNS_IPV4] umschreibt, bevor sie über NAT relayt wird — die
         * Sentinel-Adresse selbst ist außerhalb des Tunnels nicht erreichbar (s.
         * `ensure_listener_for_packet`/`pump_udp_listeners`-Kommentare). Bewusst ein fest
         * verdrahteter, öffentlicher Resolver statt eines Versuchs, den echten DNS-Server des
         * zugrundeliegenden Netzwerks zur Laufzeit zu ermitteln (z. B. über
         * `ConnectivityManager.getLinkProperties()`) — das zugrundeliegende Netzwerk eines
         * Always-On-VPN ist zur Laufzeit nicht zuverlässig ohne `setUnderlyingNetworks()`-
         * Buchführung greifbar, und dieser Aufwand steht in keinem Verhältnis zum Nutzen: der
         * eigentliche Zweck der Netz-Sperre (Kill-Switch + Blockliste) funktioniert unabhängig
         * davon bereits vollständig über den DNS-Fastpath in `try_fast_path_dns_reply` (der
         * NIEMALS einen echten Upstream-Server braucht). Cloudflares `1.1.1.1` ist ein
         * datenschutzorientierter, öffentlich dokumentierter Resolver ohne Anmeldepflicht — ein
         * bewusster, dokumentierter Kompromiss, keine versteckte Traffic-Umleitung. */
        const val UPSTREAM_DNS_IPV4 = "1.1.1.1"
        const val ACTION_STOP_TUNNEL = "de.ble1st.warden.netlock.action.STOP_TUNNEL"
        const val ACTION_RELOAD_TUNNEL = "de.ble1st.warden.netlock.action.RELOAD_TUNNEL"
    }
}
