package de.ble1st.warden.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import de.ble1st.warden.R
import de.ble1st.warden.bus.BarbicanConcordClient
import de.ble1st.warden.domain.netlock.ChildVpnConfig
import de.ble1st.warden.netlock.BarbicanEngine
import de.ble1st.warden.netlock.ChildVpnConfigStore
import de.ble1st.warden.netlock.DomainBlocklistStore
import de.ble1st.warden.netlock.NetworkFirewallPolicyStore
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
 * dieselbe APK wie Warden (kein Cross-APK-AIDL/Broadcast mehr nötig, s.
 * `CLAUDE.md`-"Netz-Sperre"-Abschnitt und [NetLockdownAuthorizer]-Klassendoc). **Seit dem
 * Prozess-Split (2026-08-31, `docs/design-barbican-prozess-childvpn.md`) ein eigener Prozess
 * (`android:process=":barbican"` im Manifest) — läuft also weiterhin nicht cross-APK, aber auch
 * nicht mehr in Wardens Hauptprozess**, damit ein Absturz/Hang der Rust-Engine nicht den
 * sicherheitskritischen Kernprozess (Presence-Gating, Boot-Reconciliation, Audit-Log) mitreißt.
 * Anders als das Quellprojekt (reiner Sinkhole, Milestone I.1) läuft hier der echte NAT-/
 * DNS-Filter-Tunnel ([BarbicanEngine.startCapturedTunnel]) — s.
 * `rust/barbican/src/engine.rs`-Moduldoc für die bewusste IPv4-only-Scope-Reduktion dieser
 * Umsetzungsrunde, deshalb hier nur eine IPv4-Adresse/-Route am [Builder].
 *
 * **ChildVPN (2026-08-31, `docs/design-barbican-prozess-childvpn.md`):** [BarbicanEngine
 * .setChildVpnConfig] wird IMMER von hier aus aufgerufen, nie direkt aus der UI — die geladene
 * `libconnexias_barbican.so` und ihr gesamter statischer Zustand (`CHILD_VPN` in `childvpn.rs`)
 * existieren pro *Prozess*, nicht pro APK; ein Aufruf aus Wardens Hauptprozess würde nur die dortige,
 * eigene (nie getunnelte) Kopie dieses Zustands setzen, ohne jede Wirkung auf den hier in `:barbican`
 * laufenden Tunnel. Die UI schreibt deshalb nur [de.ble1st.warden.netlock.ChildVpnConfigStore]
 * (eine Datei, prozessübergreifend sicher lesbar) und stößt über [de.ble1st.warden.netlock
 * .NetLockdownController.resyncChildVpn] ein `ACTION_UPDATE_CHILD_VPN` an — exakt dasselbe
 * Cross-Prozess-Muster wie [ACTION_UPDATE_BLOCKLIST]/[updateBlocklist] es für die Domain-
 * Blockliste bereits vormacht.
 *
 * **Single-Thread-`Executor` für start/stop/reload:** anders als im Quellprojekt (dortiger Main-
 * Thread-Deadlock-Fund kam von einem synchron blockierenden Cross-Prozess-Bind, das hier
 * strukturell entfällt — [NetworkFirewallPolicyStore] ist ein lokaler, verschlüsselter Datei-Read,
 * unabhängig vom Prozess kein IPC) bleibt die Auslagerung trotzdem sinnvoll: `Builder.establish()`
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

    /** Concord-Rückkanal (2026-08-31, `docs/design-barbican-prozess-childvpn.md`) — spiegelt
     * Ereignisse, die vorher nur in Logcat verschwanden (TUN-`establish()`-Fehlschlag, Rust-
     * Tunnel-Start-Exception), ins Hauptprozess-Audit-Log. Bindet in [onCreate], nicht lazy beim
     * ersten Ereignis — der Verbindungsaufbau braucht einen Moment, und genau die *ersten*
     * Ereignisse (Tunnel-Start) sind am interessantesten. */
    private lateinit var concordClient: BarbicanConcordClient

    override fun onCreate() {
        super.onCreate()
        concordClient = BarbicanConcordClient(this)
        concordClient.connect()
    }

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
            ACTION_UPDATE_BLOCKLIST -> {
                worker.execute { updateBlocklist() }
                return START_STICKY
            }
            ACTION_UPDATE_CHILD_VPN -> {
                worker.execute { updateChildVpn() }
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
        concordClient.disconnect()
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
        // KRITISCH (2026-09-01): die ChildVPN-Config MUSS vor `Builder.establish()` gelesen werden
        // — anders als früher angenommen bestimmt sie die TUN-Parameter selbst (Adresse, MTU, DNS),
        // s. [childVpnTunAddress]/[childVpnMtu]-Kommentare unten. Genau ein Lesevorgang pro
        // Tunnelstart, dessen Ergebnis unten an [applyChildVpnConfig] weitergereicht wird.
        val childVpnConfig = loadChildVpnConfig()
        val config = (childVpnConfig as? ChildVpnConfigLoad.Present)?.config
        // KRITISCH (2026-09-01, ROOT-URSACHE des ChildVPN-Fehlers "verbunden, aber kein Internet"):
        // bei scharf geschaltetem ChildVPN MUSS der TUN die von der VPS zugewiesene WireGuard-
        // Adresse tragen (`[Interface] Address`), nicht Wardens eigene [TUNNEL_IPV4]. ChildVPN ist
        // reines Layer-3-Passthrough: was der Lese-Thread aus dem TUN liest, wird unverändert
        // verschlüsselt an die VPS gesendet — inklusive der Quell-IP. WireGuards Cryptokey-Routing
        // auf der Gegenseite prüft diese Quell-IP jedes erfolgreich entschlüsselten Pakets gegen
        // `AllowedIPs` des Peers (typisch die diesem Client zugewiesene `/32`) und VERWIRFT jedes
        // Paket, das nicht passt — lautlos, nach dem Entschlüsseln, ohne jede Fehlermeldung an den
        // Client. Mit der fest verdrahteten `10.64.0.1` traf das auf ausnahmslos jedes Nutzpaket
        // zu. Das erklärt das komplette beobachtete Fehlerbild exakt: Handshake erfolgreich
        // (Schlüssel korrekt, kryptografisch bewiesen durch erfolgreich entschlüsselte Keepalives),
        // ausgehende Pakete korrekt verschlüsselt und versendet, aber niemals eine einzige echte
        // Antwort — und gleichzeitig andere Geräte an derselben VPS voll funktionsfähig (die setzen
        // per wg-quick ihre zugewiesene Adresse korrekt). Die Rückrichtung wäre übrigens genauso
        // gebrochen: an `10.66.x.y` adressierte Antwortpakete hätte der Kernel mangels passender
        // `addAddress` ohnehin verworfen.
        val tunAddress = config?.addressIpv4 ?: TUNNEL_IPV4
        val tunPrefixLength = config?.addressPrefixLength ?: TUNNEL_IPV4_PREFIX_LENGTH
        // KRITISCH (2026-09-01, im `childvpn.rs`-Moduldoc bereits als offener Punkt vermerkt): bei
        // ChildVPN steckt jedes TUN-Paket zusätzlich in einem WireGuard-Rahmen (20 Byte äußerer
        // IPv4-Header + 8 Byte UDP + 16 Byte WireGuard-Datenheader + 16 Byte Poly1305-Tag = 60
        // Byte). Mit den vollen 1500 Byte würde ein maximal großes TUN-Paket als 1560-Byte-Datagramm
        // hinausgehen und unterwegs fragmentiert oder verworfen — der klassische "kleine Seiten
        // laden, große hängen"-Fehler. [CHILD_VPN_MTU] entspricht dem wg-quick-Standardwert, der
        // zusätzlich Reserve für einen äußeren IPv6- statt IPv4-Header einplant.
        val mtu = if (config != null) CHILD_VPN_MTU else MTU
        // Eine `DNS =`-Angabe der ChildVPN-Config hat Vorrang (VPS-eigene Resolver sind oft nur aus
        // dem Tunnel heraus erreichbar), sonst bleibt es beim bisherigen [UPSTREAM_DNS_IPV4].
        // Einzige harte Bedingung ist in beiden Fällen die Abweichung von [tunAddress], s. den
        // KRITISCH-Kommentar an `.addDnsServer(...)` unten.
        val dnsServer = listOfNotNull(config?.dnsIpv4, UPSTREAM_DNS_IPV4).firstOrNull { it != tunAddress }
            ?: UPSTREAM_DNS_IPV4
        val builder = Builder()
            .setSession(getString(R.string.notification_net_lockdown_channel_name))
            .setMtu(mtu)
            .addAddress(tunAddress, tunPrefixLength)
            .addRoute("0.0.0.0", 0)
            // IPv6-Default-Route — ohne diese route ALLOWED-Apps ungefilterten IPv6-Zugang,
            // der die Domain-Blockliste komplett umgeht (AAAA-Queries an externe DNS-Server).
            .addAddress(TUNNEL_IPV6, TUNNEL_IPV6_PREFIX_LENGTH)
            .addRoute("::", 0)
            .addDnsServer(UPSTREAM_DNS_IPV6)
            // KRITISCH (2026-08-27 Live-Fund #2): der DNS-Server darf NICHT dieselbe Adresse wie
            // `addAddress` sein. Eine Adresse, die dem Interface selbst zugewiesen ist, wird vom
            // Kernel als "lokale Zustellung" behandelt — Pakete dorthin verlassen den lokalen
            // IP-Stack nie in Richtung `tun_fd`, sie werden intern zugestellt (und mangels eines
            // echten lauschenden Sockets auf `TUNNEL_IPV4:53` verworfen), bevor Wardens Lese-Thread
            // sie je zu sehen bekommt. Live auf dem Testgerät bestätigt: mit `addDnsServer
            // (TUNNEL_IPV4)` erreichten *keine* echten IPv4-Pakete (weder eigene Test-Queries noch
            // Chrome-Traffic) je die Engine — nur unabhängiges IPv6-Router-Solicitation-Rauschen
            // kam an (`v4=0, v6=N` in den Debug-Zählern). Jede von `TUNNEL_IPV4` verschiedene
            // Adresse erfüllt diese Bedingung — `engine.rs`s `set_any_ip(true)` akzeptiert ohnehin
            // jede Ziel-IP, die konkrete DNS-Server-Adresse ist der Rust-Seite gegenüber irrelevant.
            //
            // KRITISCH (2026-08-31, ChildVPN-Debugging-Session "verbunden, aber kein Internet",
            // Folgefund zum `pump_established_sessions`-Panic-Fix desselben Tages): früher stand
            // hier eine eigene, rein interne "Sentinel"-Adresse (`10.64.0.2`, außerhalb des
            // Tunnels nicht erreichbar) — Direct-Mode's NAT-Relay (`engine.rs::pump_udp_listeners`)
            // schrieb jede Anfrage dorthin auf einen echten Upstream-Resolver um, BEVOR sie
            // weitergeleitet wurde. ChildVPN (reines Layer-3-Passthrough, bewusst OHNE jede
            // Paket-Umschreibung — s. `childvpn.rs`-Moduldoc, eine vom Nutzer bereits einmal
            // explizit korrigierte frühere Planvariante) durchläuft diesen Rewrite-Schritt nie:
            // jede DNS-Anfrage ging bei scharf geschaltetem ChildVPN unverändert an die Sentinel-
            // Adresse hinaus — verschlüsselt und an die VPS gesendet, aber adressiert an eine
            // IP, die nirgends im echten Internet existiert und die die VPS folglich niemals
            // beantworten konnte, unabhängig von deren eigener Routing-/NAT-Konfiguration. Live
            // bestätigt: echte SYN-/DNS-Pakete verließen den Tunnel korrekt verschlüsselt, aber
            // keine einzige Antwort kam je zurück. Der Rewrite-Mechanismus stellte sich dabei als
            // unnötig heraus — der Blocklisten-Fastpath (`try_fast_path_dns_reply`) matcht ohnehin
            // nur auf UDP-Zielport 53, unabhängig von der Ziel-IP — deshalb jetzt direkt
            // [UPSTREAM_DNS_IPV4] hier eingetragen: identisch für beide Modi, für Direct-Mode
            // funktional unverändert (der Rewrite in `pump_udp_listeners` wird dadurch zum reinen
            // No-Op, `dst_addr == dns_sentinel` triff dann bereits zu, `real_dst` bleibt
            // unverändert), für ChildVPN jetzt eine echte, im Internet routbare Adresse, die die
            // VPS wie jeden anderen Traffic ganz gewöhnlich weiterleiten kann.
            //
            // 2026-09-01: seitdem zusätzlich die `DNS =`-Angabe der ChildVPN-Config bevorzugt,
            // falls vorhanden — s. Berechnung von [dnsServer] oben.
            .addDnsServer(dnsServer)
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
            concordClient.reportEvent(Log.ERROR, "Barbican: TUN establish() lieferte null")
            stopSelf()
            return
        }
        tunInterface = pfd
        try {
            val blocklistStore = DomainBlocklistStore(DomainBlocklistStore.buildEnvelopeFile(this))
            BarbicanEngine.setBlocklist(blocklistStore.effectiveBlocklist())
            Log.i(TAG, "Calling startCapturedTunnel(fd=${pfd.fd}, ipv4=$tunAddress/$tunPrefixLength, mtu=$mtu, dns=$dnsServer)")
            // `dns_sentinel`/`upstream_dns` bewusst identisch übergeben — s. Kommentar an
            // `.addDnsServer(...)` oben (2026-08-31-Fix): der Sentinel-Rewrite-Mechanismus in
            // `engine.rs::pump_udp_listeners` bleibt als API erhalten (kein Rust-seitiger Umbau
            // nötig), wird mit identischen Werten aber zum reinen No-Op.
            BarbicanEngine.startCapturedTunnel(pfd.fd, tunAddress, dnsServer, dnsServer, this)
            val running = BarbicanEngine.isCapturedTunnelRunning()
            Log.i(TAG, "startCapturedTunnel returned OK, running=$running")
            // running=true ist hier KEIN zuverlässiger Beweis für einen tatsächlich arbeitenden
            // Engine-Loop-Thread (s. Kommentar oben zu setBlocking(true)/dem RX-Freeze-Bug) — das
            // Concord-Rückkanal-Ziel dieses Schritts ist Sichtbarkeit im Audit-Log, nicht die
            // Erkennung des Bugs selbst.
            concordClient.reportEvent(Log.INFO, "Barbican: Tunnel gestartet (running=$running)")
            // ChildVPN (2026-08-31): eine bereits gespeicherte Config wird bei JEDEM Tunnel-Start
            // frisch angewendet — derselbe "immer neu aus dem Store lesen statt auf Rust-seitigen
            // Prozessspeicher verlassen" Grundsatz wie beim Blocklisten-Read oben zwei Zeilen
            // darüber, wichtig insbesondere nach einem `:barbican`-Prozessneustart (verliert jeden
            // statischen Rust-Zustand, s. Klassendoc).
            applyChildVpnConfig(childVpnConfig)
        } catch (e: Exception) {
            Log.e(TAG, "Rust-Tunnel-Start fehlgeschlagen", e)
            concordClient.reportEvent(Log.ERROR, "Barbican: Tunnel-Start fehlgeschlagen: $e")
            stopTunnel()
            stopSelf()
        }
    }

    /** Ergebnis von [loadChildVpnConfig] — die Unterscheidung zwischen [Absent] ("nie konfiguriert
     * bzw. gelöscht", ChildVPN wird deaktiviert) und [Unreadable] ("vorhanden, aber nicht lesbar",
     * ChildVPN bleibt unverändert) ist die im Store-Klassendoc beschriebene Fail-safe-Trennung und
     * darf nicht zu einem gemeinsamen `null` zusammenfallen. */
    private sealed interface ChildVpnConfigLoad {
        data class Present(val config: ChildVpnConfig) : ChildVpnConfigLoad
        data object Absent : ChildVpnConfigLoad
        data object Unreadable : ChildVpnConfigLoad
    }

    /** Liest die gespeicherte ChildVPN-Konfiguration genau einmal. Läuft ausschließlich auf
     * [worker]. Getrennt von [applyChildVpnConfig], weil [startTunnel] das Ergebnis schon VOR dem
     * `Builder.establish()` braucht (TUN-Adresse/MTU/DNS hängen daran, s. dortiger
     * KRITISCH-Kommentar von 2026-09-01) und es danach nicht ein zweites Mal lesen soll. */
    private fun loadChildVpnConfig(): ChildVpnConfigLoad {
        val configStore = ChildVpnConfigStore(ChildVpnConfigStore.buildEnvelopeFile(this))
        return try {
            configStore.load()?.let { ChildVpnConfigLoad.Present(it) } ?: ChildVpnConfigLoad.Absent
        } catch (e: Exception) {
            // Fail-safe: eine kaputte Config wird NICHT stillschweigend als "keine Config"
            // behandelt (s. CLAUDE.md "Fail-safe over convenient") — ChildVPN bleibt aus/unverändert,
            // der Fehler landet im Audit-Log statt eines stillen Fallbacks auf Direct-Mode.
            Log.e(TAG, "ChildVpnConfigStore.load() fehlgeschlagen", e)
            concordClient.reportEvent(Log.ERROR, "Barbican: ChildVPN-Konfiguration konnte nicht gelesen werden: $e")
            ChildVpnConfigLoad.Unreadable
        }
    }

    /** Wendet das Ergebnis von [loadChildVpnConfig] auf den Rust-Engine-Zustand dieses Prozesses an
     * — scharf schalten bei vorhandener Config, bei gelöschter deaktivieren, bei unlesbarer
     * unverändert lassen. Läuft ausschließlich auf [worker], aufgerufen von [startTunnel] (jeder
     * Tunnelstart, mit der dort ohnehin schon gelesenen Config). */
    private fun applyChildVpnConfig(loaded: ChildVpnConfigLoad) {
        val config = when (loaded) {
            is ChildVpnConfigLoad.Unreadable -> return
            is ChildVpnConfigLoad.Absent -> {
                BarbicanEngine.clearChildVpnConfig()
                return
            }
            is ChildVpnConfigLoad.Present -> loaded.config
        }
        try {
            BarbicanEngine.setChildVpnConfig(
                privateKey = config.privateKey,
                peerPublicKey = config.peerPublicKey,
                presharedKey = config.presharedKey,
                persistentKeepaliveSecs = config.persistentKeepaliveSecs?.toUShort(),
                endpointHost = config.endpointHost,
                endpointPort = config.endpointPort.toUShort(),
                socketFactory = this,
            )
            concordClient.reportEvent(Log.INFO, "Barbican: ChildVPN scharf geschaltet (${config.endpointHost}:${config.endpointPort})")
        } catch (e: Exception) {
            Log.e(TAG, "setChildVpnConfig fehlgeschlagen", e)
            concordClient.reportEvent(Log.ERROR, "Barbican: ChildVPN-Aktivierung fehlgeschlagen: $e")
        }
    }

    /** [ACTION_UPDATE_CHILD_VPN]-Handler. No-op, solange gar kein Tunnel läuft — dann liest der
     * nächste reguläre [startTunnel] die dann bereits aktuelle Config ohnehin frisch ein.
     *
     * KRITISCH (2026-09-01): braucht seitdem einen echten TUN-Neuaufbau und ist damit KEIN Mirror
     * von [updateBlocklist] mehr. Bis dahin genügte hier ein reines `applyChildVpnConfig()`, weil
     * `engine.rs::run_engine_loop`s dual-mode-Verzweigung ab dem nächsten Tick greift, ohne den
     * bestehenden Tunnel anzufassen. Seit die ChildVPN-Config auch die TUN-Parameter selbst
     * bestimmt (Adresse, MTU, DNS — s. KRITISCH-Kommentar in [startTunnel]), reicht das nicht mehr:
     * diese Werte sind an einem bereits per `Builder.establish()` aufgebauten TUN unveränderlich,
     * ein Wechsel der Config bei laufendem Tunnel würde sonst mit genau der falschen Adresse
     * weiterlaufen, die dieser Fix beseitigt. */
    private fun updateChildVpn() {
        if (tunInterface == null) return
        // `releaseForeground = false`: der Dienst läuft durch, nur der TUN wird ersetzt — sonst
        // fiele die Foreground-Notification zwischen Ab- und Neuaufbau kurz weg.
        stopTunnel(releaseForeground = false)
        startTunnel()
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
    // 2026-08-29 (gefundene Verdrahtungs-Ineffizienz): vor diesem Fix löste jede Blocklisten-
    // Änderung [ACTION_RELOAD_TUNNEL] aus — einen vollen TUN-Abbau/-Neuaufbau, der jede laufende
    // NAT-Session killt, nur um eine Domain-Liste zu aktualisieren. `engine.rs`s `BLOCKLIST` ist
    // ein vom Tunnel-Lebenszyklus unabhängiges `RwLock` (`current_blocklist()` liest bei jeder
    // einzelnen DNS-Anfrage frisch, s. `try_fast_path_dns_reply`) — [BarbicanEngine.setBlocklist]
    // wirkt sofort auf die nächste Anfrage, ganz ohne den Tunnel anzufassen. Läuft ausschließlich
    // auf [worker], liest den Store also nicht parallel zu einem laufenden `startTunnel()`.
    private fun updateBlocklist() {
        if (tunInterface == null) return
        val blocklistStore = DomainBlocklistStore(DomainBlocklistStore.buildEnvelopeFile(this))
        BarbicanEngine.setBlocklist(blocklistStore.effectiveBlocklist())
    }

    private fun stopTunnel(releaseForeground: Boolean = true) {
        tunInterface?.close()
        tunInterface = null
        // 2026-08-31: der Fehler landete bis hierher nur in runCatching's verschlucktem Result,
        // nirgends sichtbar. Jetzt zumindest ins Audit-Log — kein neuer Fix für den Fehler selbst,
        // nur die im Design-Dok versprochene Sichtbarkeit.
        runCatching { BarbicanEngine.stopCapturedTunnel() }
            .onFailure { concordClient.reportEvent(Log.WARN, "Barbican: stopCapturedTunnel() fehlgeschlagen: $it") }
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
            NotificationChannel(channelId, getString(R.string.notification_net_lockdown_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_net_lockdown_title))
            .setContentText(getString(R.string.notification_net_lockdown_text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "WardenVpn"
        private const val MTU = 1500
        /** MTU bei scharf geschaltetem ChildVPN — der wg-quick-Standardwert. Begründung s.
         * KRITISCH-Kommentar an der Berechnung von `mtu` in [startTunnel]. */
        private const val CHILD_VPN_MTU = 1420
        private const val NOTIFICATION_ID = 42
        private const val CONNECT_TIMEOUT_MS = 10_000
        /** Tunnel-eigene IPv4-Adresse (`addAddress`) im Direct-Mode — dient als
         * `any_ip`-Default-Route-Gateway auf der Rust-Seite (s. `engine.rs`-Moduldoc). NICHT als
         * DNS-Server-Adresse verwenden (s. [UPSTREAM_DNS_IPV4]-Doc).
         *
         * Gilt seit dem 2026-09-01 ausdrücklich NUR für den Direct-Mode: bei scharf geschaltetem
         * ChildVPN wird stattdessen die von der VPS zugewiesene `[Interface] Address` verwendet,
         * s. KRITISCH-Kommentar in [startTunnel]. */
        const val TUNNEL_IPV4 = "10.64.0.1"
        /** Präfixlänge zu [TUNNEL_IPV4] — eine Host-Route, der Direct-Mode braucht kein Subnetz. */
        private const val TUNNEL_IPV4_PREFIX_LENGTH = 32
        /** DNS-Server-Adresse (`addDnsServer`) UND `dns_sentinel`/`upstream_dns` gegenüber
         * `startCapturedTunnel` — bewusst dieselbe Adresse an allen drei Stellen (2026-08-31-Fix,
         * ChildVPN-Debugging-Session "verbunden, aber kein Internet"). Bis dahin stand hier für
         * `addDnsServer` eine eigene, rein interne "Sentinel"-Adresse (`10.64.0.2`), die
         * `engine.rs::pump_udp_listeners` nur im Direct-Mode-NAT-Relay-Pfad auf diesen echten
         * Resolver umschrieb — ChildVPN (reines Layer-3-Passthrough ohne jede Paket-Umschreibung,
         * s. `childvpn.rs`-Moduldoc) durchläuft diesen Rewrite nie, jede DNS-Anfrage ging bei
         * scharf geschaltetem ChildVPN unverändert an eine Adresse hinaus, die nirgends im echten
         * Internet existiert und die die VPS folglich niemals beantworten konnte. Der
         * Rewrite-Mechanismus war ohnehin unnötig: der Blocklisten-Fastpath
         * (`try_fast_path_dns_reply`) matcht rein auf UDP-Zielport 53, unabhängig von der Ziel-IP
         * — die einzige Bedingung an die `addDnsServer`-Adresse ist, dass sie von [TUNNEL_IPV4]
         * abweicht (sonst behandelt der Kernel Pakete dorthin als lokale Zustellung, s. Kommentar
         * an der `addDnsServer`-Aufrufstelle), eine feste Sentinel-Adresse war dafür nie
         * erforderlich. Für Direct-Mode funktional unverändert (der Rewrite in
         * `pump_udp_listeners` wird durch die jetzt identischen Werte zum reinen No-Op), für
         * ChildVPN jetzt eine echte, im Internet routbare Adresse.
         *
         * Bewusst ein fest verdrahteter, öffentlicher Resolver statt eines Versuchs, den echten
         * DNS-Server des zugrundeliegenden Netzwerks zur Laufzeit zu ermitteln (z. B. über
         * `ConnectivityManager.getLinkProperties()`) — das zugrundeliegende Netzwerk eines
         * Always-On-VPN ist zur Laufzeit nicht zuverlässig ohne `setUnderlyingNetworks()`-
         * Buchführung greifbar, und dieser Aufwand steht in keinem Verhältnis zum Nutzen: der
         * eigentliche Zweck der Netz-Sperre (Kill-Switch + Blockliste) funktioniert unabhängig
         * davon bereits vollständig über den DNS-Fastpath in `try_fast_path_dns_reply` (der
         * NIEMALS einen echten Upstream-Server braucht). Cloudflares `1.1.1.1` ist ein
         * datenschutzorientierter, öffentlich dokumentierter Resolver ohne Anmeldepflicht — ein
         * bewusster, dokumentierter Kompromiss, keine versteckte Traffic-Umleitung. */
        const val UPSTREAM_DNS_IPV4 = "1.1.1.1"
        /** IPv6-Tunnel-Adresse — eine ULA, die nicht mit echtem Internet kollidiert. */
        private const val TUNNEL_IPV6 = "fd00:dead:beef::1"
        private const val TUNNEL_IPV6_PREFIX_LENGTH = 128
        /** IPv6-DNS-Server — Cloudflares öffentlicher IPv6-Resolver. */
        private const val UPSTREAM_DNS_IPV6 = "2606:4700:4700::1111"
        const val ACTION_STOP_TUNNEL = "de.ble1st.warden.vpn.action.STOP_TUNNEL"
        const val ACTION_RELOAD_TUNNEL = "de.ble1st.warden.vpn.action.RELOAD_TUNNEL"
        /** s. [updateBlocklist]-Kommentar — bewusst getrennt von [ACTION_RELOAD_TUNNEL]: eine
         * Blocklisten-Änderung braucht anders als eine Firewall-Allowlist-Änderung keinen
         * TUN-Neuaufbau. */
        const val ACTION_UPDATE_BLOCKLIST = "de.ble1st.warden.vpn.action.UPDATE_BLOCKLIST"
        /** Anders als [ACTION_UPDATE_BLOCKLIST] eben NICHT mehr ohne TUN-Neuaufbau: s.
         * [updateChildVpn]-Kommentar (KRITISCH, 2026-09-01) — seit die ChildVPN-Config auch die
         * TUN-Parameter selbst bestimmt, braucht eine Konfigurationsänderung zwingend einen
         * echten Neuaufbau. (Korrigiert 2026-09-05: dieser Kommentar behauptete bis dahin fälschlich
         * das Gegenteil — ein bei jenem Fix nicht mitgezogener Rest aus der Zeit davor, gefunden
         * von einer externen Bugsuche, s. `mistral-analyse.md`.) */
        const val ACTION_UPDATE_CHILD_VPN = "de.ble1st.warden.vpn.action.UPDATE_CHILD_VPN"
    }
}
