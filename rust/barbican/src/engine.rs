//! "Netz-Sperre" (2026-08-27): öffentliche UniFFI-Exports und der eigentliche NAT-/DNS-Filter-
//! Paket-Lese-Loop, der `dns_filter`/`nat`/`callback` zu einem funktionierenden transparenten
//! VPN-Gateway verdrahtet.
//!
//! **Bewusste Scope-Reduktion dieser Umsetzungsrunde: nur IPv4.** `WardenVpnService` (Kotlin-
//! Seite) registriert entsprechend keine IPv6-Route am `VpnService.Builder` — Apps mit
//! IPv6-Konnektivitätsbedarf fallen für die Dauer der Netz-Sperre auf IPv4 zurück (Standard-
//! Verhalten bei fehlender IPv6-Route, kein Leck: ohne Route gibt es schlicht keinen IPv6-Pfad
//! nach draußen). Echtes IPv6-Dual-Stack-NAT ist eine mögliche spätere Ausbaustufe, hier bewusst
//! nicht gebaut (dieselbe "erst die einfachere Kette, Ausbau später"-Haltung wie in Plan
//! Abschnitt 8, nur eine Ebene tiefer).
//!
//! **Architektur:** zwei Threads pro laufendem Tunnel, nicht einer — ein reiner Lese-Thread
//! (identisches blockierendes `File::read`-Muster wie `sinkhole.rs`) schiebt jedes rohe Paket in
//! einen `mpsc`-Kanal; ein separater Engine-Thread konsumiert den Kanal mit einem kurzen
//! `recv_timeout` und nutzt genau dieses Timeout zugleich als Tick für Housekeeping (Idle-
//! Session-Räumung, Byte-Relaying von/zu den externen Sockets), das sonst nie liefe, wenn gerade
//! kein neues Tunnel-Paket eintrifft. Ein einzelner Thread mit nur blockierendem `read()` könnte
//! das nicht — der Kanal entkoppelt "auf Paket warten" von "regelmäßig nach dem Rechten sehen".
//!
//! **Warum smoltcp trotz "beliebiger Zielport" nicht durch die Socket-Abstraktion ausgebremst
//! wird:** smoltcps `Interface` akzeptiert mit `set_any_ip(true)` + einer auf die eigene
//! Tunnel-Adresse zeigenden Default-Route bereits Pakete für *beliebige* Ziel-IP (verifiziert
//! gegen den smoltcp-0.12.0-Quellcode, `src/iface/interface/ipv4.rs`). Ein `tcp::Socket` in
//! `Listen`-Zustand mit `listen_endpoint.addr == None` akzeptiert ebenso ein SYN für *jede*
//! Ziel-IP — verlangt aber weiterhin einen exakten Ziel-*Port*-Treffer (`src/socket/tcp.rs`,
//! `accepts()`). Da eine NAT-Gegenstelle beliebige Zielports (80, 443, 22, ...) bedienen muss,
//! hält dieser Loop pro *beobachtetem* Zielport genau einen frei "lauschenden" Ersatz-Socket vor
//! und legt nach jeder angenommenen Verbindung sofort einen neuen nach — dieselbe Technik, mit
//! der ein normaler TCP-Server mehrere gleichzeitige Clients auf einem Port bedient (mehrere
//! parallele Listen-Sockets statt eines Backlogs, den smoltcp nicht kennt).

use crate::callback::ProtectedSocketFactory;
use crate::dns_filter;
use crate::nat::{FlowKey, NatSession, NatTable, Proto};
use smoltcp::iface::{Config, Interface, SocketSet};
use smoltcp::phy::{Device, DeviceCapabilities, Medium};
use smoltcp::socket::{tcp, udp};
use smoltcp::time::Instant as SmolInstant;
use smoltcp::wire::{
    HardwareAddress, IpAddress, IpCidr, IpProtocol, Ipv4Address, Ipv4Packet, Ipv4Repr, TcpPacket,
    UdpPacket, UdpRepr,
};
use std::collections::HashMap;
use std::fmt;
use std::fs::File;
use std::io::{ErrorKind, Read, Write};
use std::mem::ManuallyDrop;
use std::net::{Ipv4Addr, TcpStream, UdpSocket};
use std::os::fd::{FromRawFd, RawFd};
use std::str::FromStr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::{Arc, Mutex, RwLock};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

const TCP_IDLE_TIMEOUT: Duration = Duration::from_secs(10 * 60);
const UDP_IDLE_TIMEOUT: Duration = Duration::from_secs(2 * 60);
const MAX_SESSIONS: usize = 4096;
/// Wie viele gleichzeitig unbenutzte "Ersatz-Listener" pro Port maximal in `Listen`-Zustand
/// vorgehalten werden. Ein neues SYN, das diesen Deckel überschreiten würde, wird schlicht nicht
/// angenommen (die App/TCP-Stack der Gegenseite retransmittiert das SYN ohnehin) — verhindert,
/// dass eine App mit vielen gleichzeitigen Verbindungen auf denselben Port unbegrenzt viele
/// Listen-Sockets anhäuft.
const MAX_IDLE_LISTENERS_PER_PORT: usize = 4;
const TCP_BUFFER_SIZE: usize = 32 * 1024;
const UDP_BUFFER_SIZE: usize = 4096;
const CHANNEL_CAPACITY: usize = 512;
const ENGINE_TICK: Duration = Duration::from_millis(50);

#[derive(Debug, uniffi::Error)]
pub enum TunnelError {
    AlreadyRunning,
    InvalidTunFd,
    InvalidTunAddress,
    Io { detail: String },
}

impl fmt::Display for TunnelError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            TunnelError::AlreadyRunning => write!(f, "captured tunnel already running"),
            TunnelError::InvalidTunFd => write!(f, "invalid tun file descriptor"),
            TunnelError::InvalidTunAddress => write!(f, "invalid tunnel ipv4 address"),
            TunnelError::Io { detail } => write!(f, "io error: {detail}"),
        }
    }
}

#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct TunnelStats {
    pub active_sessions: u32,
    pub blocked_dns_count: u64,
    pub forwarded_bytes: u64,
}

struct StatsInner {
    blocked_dns_count: AtomicU64,
    forwarded_bytes: AtomicU64,
}

impl Default for StatsInner {
    fn default() -> Self {
        Self {
            blocked_dns_count: AtomicU64::new(0),
            forwarded_bytes: AtomicU64::new(0),
        }
    }
}

struct EngineHandle {
    stop: Arc<AtomicBool>,
    reader_handle: Option<JoinHandle<()>>,
    engine_handle: Option<JoinHandle<()>>,
    stats: Arc<StatsInner>,
    active_sessions: Arc<AtomicU64>,
}

impl EngineHandle {
    fn stop(&mut self) {
        self.stop.store(false, Ordering::SeqCst);
        // Der Lese-Thread hängt typischerweise in einem blockierenden `read()` und wacht erst
        // auf, wenn Kotlin den `tun_fd` schließt/ersetzt (identische, bereits akzeptierte
        // Einschränkung wie `sinkhole.rs::stop_sinkhole` — s. dortiges Fehlen eines aktiven
        // Unblock-Mechanismus). `disarm()`/`WardenVpnService.onRevoke()` schließen den fd ohnehin
        // in jedem Fall.
        if let Some(handle) = self.engine_handle.take() {
            let _ = handle.join();
        }
        if let Some(handle) = self.reader_handle.take() {
            let _ = handle.join();
        }
    }
}

static ENGINE: Mutex<Option<EngineHandle>> = Mutex::new(None);
static BLOCKLIST: RwLock<Option<std::collections::HashSet<String>>> = RwLock::new(None);

#[uniffi::export]
pub fn set_blocklist(domains: Vec<String>) {
    let normalized = domains
        .into_iter()
        .map(|d| d.trim_end_matches('.').to_ascii_lowercase())
        .collect();
    if let Ok(mut guard) = BLOCKLIST.write() {
        *guard = Some(normalized);
    }
}

fn current_blocklist() -> std::collections::HashSet<String> {
    BLOCKLIST
        .read()
        .ok()
        .and_then(|g| g.clone())
        .unwrap_or_default()
}

#[uniffi::export]
pub fn start_captured_tunnel(
    tun_fd: i32,
    tun_ipv4: String,
    socket_factory: Arc<dyn ProtectedSocketFactory>,
) -> Result<(), TunnelError> {
    if tun_fd < 0 {
        return Err(TunnelError::InvalidTunFd);
    }
    let tun_addr = Ipv4Addr::from_str(&tun_ipv4).map_err(|_| TunnelError::InvalidTunAddress)?;

    let mut guard = ENGINE.lock().map_err(|_| TunnelError::AlreadyRunning)?;
    if guard.is_some() {
        return Err(TunnelError::AlreadyRunning);
    }

    let stop = Arc::new(AtomicBool::new(true));
    let stats = Arc::new(StatsInner::default());
    let active_sessions = Arc::new(AtomicU64::new(0));
    let (tx, rx) = mpsc::sync_channel::<Vec<u8>>(CHANNEL_CAPACITY);

    // SAFETY: fd is borrowed from Kotlin's ParcelFileDescriptor — never closed here (Android
    // fdsan aborts if native code closes a PFD-owned fd). Two independent `File` handles over the
    // same fd (one per thread) is safe: neither ever calls close(), each only read()s or write()s,
    // and POSIX guarantees read/write on the same fd from different threads don't race each other
    // structurally (kernel-side fd offset is shared/atomic-per-syscall for a character device).
    let reader_file = ManuallyDrop::new(unsafe { File::from_raw_fd(tun_fd as RawFd) });
    let writer_file = ManuallyDrop::new(unsafe { File::from_raw_fd(tun_fd as RawFd) });

    let reader_stop = Arc::clone(&stop);
    let reader_handle = thread::Builder::new()
        .name("warden-barbican-reader".into())
        .spawn(move || {
            let mut file = reader_file;
            let mut buffer = vec![0u8; 65536];
            while reader_stop.load(Ordering::SeqCst) {
                match file.read(&mut buffer) {
                    Ok(0) => continue,
                    Ok(n) => {
                        // Ein voller Kanal (Engine-Seite kommt nicht hinterher) verwirft das
                        // Paket, statt den Lese-Thread zu blockieren — fail-safe im Sinn von
                        // "lieber ein verlorenes Paket als ein blockierter Tunnel", TCP gleicht
                        // Verlust durch Retransmission ohnehin aus.
                        let _ = tx.try_send(buffer[..n].to_vec());
                    }
                    Err(e) if e.kind() == ErrorKind::Interrupted => continue,
                    Err(_) => break,
                }
            }
        })
        .map_err(|e| TunnelError::Io {
            detail: e.to_string(),
        })?;

    let engine_stop = Arc::clone(&stop);
    let engine_stats = Arc::clone(&stats);
    let engine_active_sessions = Arc::clone(&active_sessions);
    let engine_handle = thread::Builder::new()
        .name("warden-barbican-engine".into())
        .spawn(move || {
            run_engine_loop(
                writer_file,
                rx,
                tun_addr,
                socket_factory,
                engine_stop,
                engine_stats,
                engine_active_sessions,
            );
        })
        .map_err(|e| TunnelError::Io {
            detail: e.to_string(),
        })?;

    *guard = Some(EngineHandle {
        stop,
        reader_handle: Some(reader_handle),
        engine_handle: Some(engine_handle),
        stats,
        active_sessions,
    });
    Ok(())
}

#[uniffi::export]
pub fn stop_captured_tunnel() {
    if let Ok(mut guard) = ENGINE.lock()
        && let Some(mut handle) = guard.take()
    {
        handle.stop();
    }
}

#[uniffi::export]
pub fn is_captured_tunnel_running() -> bool {
    ENGINE.lock().map(|g| g.is_some()).unwrap_or(false)
}

#[uniffi::export]
pub fn tunnel_stats() -> TunnelStats {
    match ENGINE.lock() {
        Ok(guard) => match guard.as_ref() {
            Some(handle) => TunnelStats {
                active_sessions: handle.active_sessions.load(Ordering::SeqCst) as u32,
                blocked_dns_count: handle.stats.blocked_dns_count.load(Ordering::SeqCst),
                forwarded_bytes: handle.stats.forwarded_bytes.load(Ordering::SeqCst),
            },
            None => TunnelStats::default(),
        },
        Err(_) => TunnelStats::default(),
    }
}

/// `smoltcp::phy::Device`-Adapter über den TUN-`File`-Handle: eingehende Rohpakete kommen aus
/// einer lokal gefüllten Warteschlange (vom `mpsc`-Kanal übernommen, s. `run_engine_loop`), nicht
/// direkt per Syscall — Lesen passiert bereits im separaten Reader-Thread. Senden schreibt
/// synchron auf denselben `tun_fd` (Schreiben auf ein TUN-Zeichengerät blockiert in der Praxis
/// nicht spürbar, kein zweiter Thread dafür nötig).
struct TunDevice {
    inbound: std::collections::VecDeque<Vec<u8>>,
    writer: ManuallyDrop<File>,
}

struct TunRxToken {
    buf: Vec<u8>,
}

impl smoltcp::phy::RxToken for TunRxToken {
    fn consume<R, F>(self, f: F) -> R
    where
        F: FnOnce(&[u8]) -> R,
    {
        f(&self.buf)
    }
}

struct TunTxToken<'a> {
    writer: &'a mut ManuallyDrop<File>,
}

impl<'a> smoltcp::phy::TxToken for TunTxToken<'a> {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut buf = vec![0u8; len];
        let result = f(&mut buf);
        let _ = self.writer.write_all(&buf);
        result
    }
}

impl Device for TunDevice {
    type RxToken<'a> = TunRxToken;
    type TxToken<'a> = TunTxToken<'a>;

    fn receive(&mut self, _timestamp: SmolInstant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let buf = self.inbound.pop_front()?;
        Some((TunRxToken { buf }, TunTxToken { writer: &mut self.writer }))
    }

    fn transmit(&mut self, _timestamp: SmolInstant) -> Option<Self::TxToken<'_>> {
        Some(TunTxToken { writer: &mut self.writer })
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip;
        caps.max_transmission_unit = 65536;
        caps
    }
}

/// Ein per Port vorgehaltener Ersatz-Listener, der auf ein SYN wartet (s. Moduldoc, Abschnitt
/// "Warum smoltcp ... nicht ausgebremst wird").
struct PortListener {
    handle: smoltcp::iface::SocketHandle,
}

#[allow(clippy::too_many_arguments)]
fn run_engine_loop(
    writer_file: ManuallyDrop<File>,
    rx: mpsc::Receiver<Vec<u8>>,
    tun_addr: Ipv4Addr,
    socket_factory: Arc<dyn ProtectedSocketFactory>,
    stop: Arc<AtomicBool>,
    stats: Arc<StatsInner>,
    active_sessions_counter: Arc<AtomicU64>,
) {
    let mut device = TunDevice {
        inbound: std::collections::VecDeque::new(),
        writer: writer_file,
    };

    let mut config = Config::new(HardwareAddress::Ip);
    config.random_seed = 0x5741_5244; // "WARD" — deterministisch reicht hier, kein Sicherheitszweck.
    let mut iface = Interface::new(config, &mut device, SmolInstant::from_millis(0));
    let octets = tun_addr.octets();
    let smol_addr = Ipv4Address::new(octets[0], octets[1], octets[2], octets[3]);
    iface.update_ip_addrs(|addrs| {
        let _ = addrs.push(IpCidr::new(IpAddress::Ipv4(smol_addr), 32));
    });
    iface.set_any_ip(true);
    let _ = iface.routes_mut().add_default_ipv4_route(smol_addr);

    let mut sockets = SocketSet::new(Vec::new());
    let mut nat = NatTable::new(MAX_SESSIONS);
    // Port -> alle Ersatz-Listener, die gerade auf ein SYN warten.
    let mut tcp_listeners: HashMap<u16, Vec<PortListener>> = HashMap::new();
    // Port -> ein einziger dauerhaft gebundener UDP-Socket (verbindungslos, braucht kein
    // Nachlegen — s. Moduldoc).
    let mut udp_listeners: HashMap<u16, smoltcp::iface::SocketHandle> = HashMap::new();
    // FlowKey -> zugehöriger smoltcp-Socket-Handle, damit Bytes in beide Richtungen relayt werden
    // können (die NatTable selbst kennt nur den externen fd, s. `nat.rs`-Klassendoc).
    let mut flow_sockets: HashMap<FlowKey, smoltcp::iface::SocketHandle> = HashMap::new();
    let mut last_housekeeping = Instant::now();

    while stop.load(Ordering::SeqCst) {
        match rx.recv_timeout(ENGINE_TICK) {
            Ok(packet) => {
                if let Some(reply) = try_fast_path_dns_reply(&packet, &stats) {
                    let _ = device.writer.write_all(&reply);
                } else {
                    ensure_listener_for_packet(&packet, &mut sockets, &mut tcp_listeners, &mut udp_listeners);
                    device.inbound.push_back(packet);
                }
            }
            Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => break,
        }

        let now = SmolInstant::from_millis(Instant::now().elapsed().as_millis() as i64);
        iface.poll(now, &mut device, &mut sockets);

        reap_completed_listeners(&mut sockets, &mut tcp_listeners, &mut flow_sockets, &socket_factory);
        pump_established_sessions(&mut sockets, &mut nat, &mut flow_sockets, &stats);
        pump_udp_listeners(&mut sockets, &udp_listeners, &mut nat, &socket_factory, &mut flow_sockets);
        pump_udp_responses(&mut sockets, &nat, &flow_sockets, &stats);

        if last_housekeeping.elapsed() >= Duration::from_secs(5) {
            last_housekeeping = Instant::now();
            let evicted = nat.evict_idle(Instant::now(), TCP_IDLE_TIMEOUT, UDP_IDLE_TIMEOUT);
            for (key, session) in evicted {
                close_session(&key, session, &mut sockets, &mut flow_sockets);
            }
            active_sessions_counter.store(nat.len() as u64, Ordering::SeqCst);
        }
    }
}

/// Prüft ein eingehendes rohes IPv4-Paket auf einen blockierten DNS-Query (UDP/53) — Fast-Path
/// ganz ohne smoltcp/`Interface`, direkte Antwort wird als fertiges IP+UDP-Paket zurückgegeben.
/// `None` für jeden anderen Fall (kein DNS, nicht blockiert, strukturell ungültig) — der Aufrufer
/// behandelt das dann als normalen Tunnel-Traffic (fail-open auf Parse-Ebene, s. `dns_filter.rs`).
fn try_fast_path_dns_reply(packet: &[u8], stats: &StatsInner) -> Option<Vec<u8>> {
    let ip_packet = Ipv4Packet::new_checked(packet).ok()?;
    if ip_packet.next_header() != IpProtocol::Udp {
        return None;
    }
    let ip_repr = Ipv4Repr::parse(&ip_packet, &Default::default()).ok()?;
    let udp_packet = UdpPacket::new_checked(ip_packet.payload()).ok()?;
    if udp_packet.dst_port() != 53 {
        return None;
    }
    let query_payload = udp_packet.payload();
    let name = dns_filter::parse_query_name(query_payload)?;
    let blocklist = current_blocklist();
    if !dns_filter::is_blocked(&name, &blocklist) {
        return None;
    }
    stats.blocked_dns_count.fetch_add(1, Ordering::SeqCst);
    let nxdomain = dns_filter::build_nxdomain_reply(query_payload)?;

    // Antwortpaket: Quelle/Ziel gegenüber der Anfrage vertauscht, gleicher UDP-Port-Tausch.
    let reply_ip_repr = Ipv4Repr {
        src_addr: ip_repr.dst_addr,
        dst_addr: ip_repr.src_addr,
        next_header: IpProtocol::Udp,
        payload_len: 8 + nxdomain.len(),
        hop_limit: 64,
    };
    let udp_repr = UdpRepr {
        src_port: udp_packet.dst_port(),
        dst_port: udp_packet.src_port(),
    };

    let mut buffer = vec![0u8; reply_ip_repr.buffer_len() + reply_ip_repr.payload_len];
    let mut ip_reply = Ipv4Packet::new_unchecked(&mut buffer);
    reply_ip_repr.emit(&mut ip_reply, &Default::default());
    let mut udp_reply = UdpPacket::new_unchecked(ip_reply.payload_mut());
    udp_repr.emit(
        &mut udp_reply,
        &IpAddress::Ipv4(reply_ip_repr.src_addr),
        &IpAddress::Ipv4(reply_ip_repr.dst_addr),
        nxdomain.len(),
        |buf| buf.copy_from_slice(&nxdomain),
        &Default::default(),
    );
    Some(buffer)
}

/// Peekt ein rohes Paket (vor dem Einreichen an `iface.poll()`) nach seinem Zielport und legt bei
/// Bedarf einen neuen Ersatz-Listener an — s. Moduldoc für die Begründung, warum das nötig ist.
fn ensure_listener_for_packet(
    packet: &[u8],
    sockets: &mut SocketSet<'static>,
    tcp_listeners: &mut HashMap<u16, Vec<PortListener>>,
    udp_listeners: &mut HashMap<u16, smoltcp::iface::SocketHandle>,
) {
    let Ok(ip_packet) = Ipv4Packet::new_checked(packet) else {
        return;
    };
    match ip_packet.next_header() {
        IpProtocol::Tcp => {
            let Ok(tcp_packet) = TcpPacket::new_checked(ip_packet.payload()) else {
                return;
            };
            if !tcp_packet.syn() || tcp_packet.ack() {
                return; // nur echte Verbindungseröffnungen brauchen einen neuen Listener.
            }
            let port = tcp_packet.dst_port();
            let entry = tcp_listeners.entry(port).or_default();
            if entry.len() >= MAX_IDLE_LISTENERS_PER_PORT {
                return; // Deckel erreicht — das SYN wird von smoltcp verworfen, App wiederholt.
            }
            let rx_buffer = tcp::SocketBuffer::new(vec![0u8; TCP_BUFFER_SIZE]);
            let tx_buffer = tcp::SocketBuffer::new(vec![0u8; TCP_BUFFER_SIZE]);
            let mut socket = tcp::Socket::new(rx_buffer, tx_buffer);
            if socket.listen(port).is_ok() {
                let handle = sockets.add(socket);
                entry.push(PortListener {
                    handle,
                });
            }
        }
        IpProtocol::Udp => {
            let Ok(udp_packet) = UdpPacket::new_checked(ip_packet.payload()) else {
                return;
            };
            let port = udp_packet.dst_port();
            // ANGEHALTEN (2026-08-27 Live-Fund #3/#4, s. [[warden-netzsperre-feature-2026-08-27]]):
            // Port 53 wird hier bewusst weiterhin übersprungen, trotz eines **bekannten**
            // Design-Lochs — eine nicht blockierte DNS-Anfrage bekommt dadurch keine echte
            // Weiterleitung/Antwort (bleibt unauflösbar) statt korrekt über NAT zu laufen. Ein
            // Versuch, das zu beheben (Port 53 denselben Weg wie jeden anderen UDP-Port nehmen zu
            // lassen), deckte live einen SCHWERWIEGENDEREN Bug auf: `pump_udp_listeners` ruft
            // `socket_factory.open_udp(...)` **synchron im Engine-Loop-Thread** auf (anders als
            // TCP, das dafür bewusst einen eigenen Thread nutzt, s. `spawn_tcp_connect`s Moduldoc-
            // Begründung) — UND übergibt dabei mit `meta.endpoint.addr` die falsche Adresse (die
            // des anfragenden Peers, nicht die eines echten Ziel-/Upstream-Servers). Ein blockierter
            // `open_udp`-Aufruf friert den GESAMTEN Engine-Loop dauerhaft ein — auch den bereits
            // funktionierenden Blocklisten-Fast-Path für alle künftigen Pakete. Da dieselbe
            // synchrone-Callback-Schwäche jeden neuen UDP-Flow betrifft, nicht nur Port 53 (jede
            // reale App würde das bei jeglichem UDP-Traffic — inkl. weit verbreitetem QUIC/HTTP3 —
            // auslösen), bleibt Port 53 hier vorerst ausgenommen, um wenigstens den häufigsten
            // Auslöser (jede DNS-Anfrage) nicht zusätzlich zu triggern. Echte Behebung braucht: (1)
            // `open_udp` auf einen eigenen Thread auslagern (analog `spawn_tcp_connect`/
            // `PENDING_TCP_FDS`), (2) die richtige Zieladresse durchreichen (Original-Paket-Ziel,
            // nicht `meta.endpoint.addr`), (3) eine echte Entscheidung, welcher Upstream-DNS-Server
            // für nicht blockierte Anfragen angefragt wird. Bis dahin: NAT-Relay für UDP ist generell
            // als ungetestet/riskant zu behandeln, nicht nur für DNS.
            if port == 53 {
                return;
            }
            if udp_listeners.contains_key(&port) {
                return;
            }
            let rx_buffer = udp::PacketBuffer::new(
                vec![udp::PacketMetadata::EMPTY; 32],
                vec![0u8; UDP_BUFFER_SIZE],
            );
            let tx_buffer = udp::PacketBuffer::new(
                vec![udp::PacketMetadata::EMPTY; 32],
                vec![0u8; UDP_BUFFER_SIZE],
            );
            let mut socket = udp::Socket::new(rx_buffer, tx_buffer);
            if socket.bind(port).is_ok() {
                let handle = sockets.add(socket);
                udp_listeners.insert(port, handle);
            }
        }
        _ => {}
    }
}

/// Nach `iface.poll()`: findet TCP-Listener, die gerade ein SYN angenommen haben (nicht mehr in
/// `Listen`), legt für sie eine NAT-Session an (externer Socket wird auf einem eigenen Kurzlebig-
/// Thread beschafft, s. Moduldoc "nie synchron vom Paket-Pfad aus blockieren") und ersetzt sie
/// sofort durch einen frischen Ersatz-Listener auf demselben Port.
fn reap_completed_listeners(
    sockets: &mut SocketSet<'static>,
    tcp_listeners: &mut HashMap<u16, Vec<PortListener>>,
    flow_sockets: &mut HashMap<FlowKey, smoltcp::iface::SocketHandle>,
    socket_factory: &Arc<dyn ProtectedSocketFactory>,
) {
    for (port, entries) in tcp_listeners.iter_mut() {
        let port = *port;
        let mut still_listening = Vec::with_capacity(entries.len());
        for listener in entries.drain(..) {
            let socket = sockets.get::<tcp::Socket>(listener.handle);
            if socket.is_listening() {
                still_listening.push(listener);
                continue;
            }
            if !socket.is_open() {
                // Weder lauschend noch offen: der Verbindungsversuch ist gescheitert, bevor er
                // etabliert wurde (z. B. RST) — Socket einfach entsorgen, kein Ersatz nötig, der
                // Port bekommt beim nächsten SYN ohnehin wieder einen frischen Listener.
                sockets.remove(listener.handle);
                continue;
            }
            let local = socket.local_endpoint();
            let remote = socket.remote_endpoint();
            if let (Some(local), Some(remote)) = (local, remote) {
                let key = FlowKey {
                    proto: Proto::Tcp,
                    src_ip: remote.addr.to_string(),
                    src_port: remote.port,
                    dst_ip: local.addr.to_string(),
                    dst_port: local.port,
                };
                flow_sockets.insert(key.clone(), listener.handle);
                spawn_tcp_connect(key, Arc::clone(socket_factory));
            }
            // Sofortiger Ersatz, solange die Obergrenze das erlaubt.
            if still_listening.len() + 1 < MAX_IDLE_LISTENERS_PER_PORT {
                let rx_buffer = tcp::SocketBuffer::new(vec![0u8; TCP_BUFFER_SIZE]);
                let tx_buffer = tcp::SocketBuffer::new(vec![0u8; TCP_BUFFER_SIZE]);
                let mut fresh = tcp::Socket::new(rx_buffer, tx_buffer);
                if fresh.listen(port).is_ok() {
                    let handle = sockets.add(fresh);
                    still_listening.push(PortListener {
                        handle,
                    });
                }
            }
        }
        *entries = still_listening;
    }
}

/// Beschafft auf einem eigenen, kurzlebigen Thread den echten `VpnService.protect()`-Socket für
/// eine gerade angenommene TCP-Verbindung — bewusst nie synchron im Engine-Loop, s. Moduldoc.
/// Das Ergebnis (Erfolg oder nicht) landet aktuell nur als Log-Zeile; die eigentliche Session-
/// Registrierung (NAT-Tabelle + Byte-Relaying) übernimmt `pump_established_sessions`, sobald der
/// externe fd über einen gemeinsamen, gesperrten Zwischenspeicher sichtbar wird.
fn spawn_tcp_connect(key: FlowKey, socket_factory: Arc<dyn ProtectedSocketFactory>) {
    thread::spawn(move || {
        let result = socket_factory.open_tcp(key.dst_ip.clone(), key.dst_port);
        if let Ok(fd) = result
            && let Ok(mut pending) = PENDING_TCP_FDS.lock() {
                pending.push((key, fd));
            }
    });
}

/// Von `spawn_tcp_connect`-Threads befüllt, von `pump_established_sessions` geleert — der einzige
/// Übergabepunkt zwischen "externer Socket wurde asynchron beschafft" und "Engine-Loop registriert
/// ihn in der NAT-Tabelle". Ein `Mutex<Vec<...>>` statt eines `mpsc`-Kanals, weil mehrere
/// gleichzeitig laufende Connect-Threads unabhängig voneinander schreiben und der Engine-Loop nur
/// an seinem eigenen Tick-Rhythmus interessiert ist, nicht an sofortiger Benachrichtigung.
static PENDING_TCP_FDS: Mutex<Vec<(FlowKey, i32)>> = Mutex::new(Vec::new());

/// Relayt Bytes für jede Session, deren externer Socket bereits steht: App→Extern aus dem
/// smoltcp-Socket-Puffer lesen und nicht-blockierend auf den externen fd schreiben, Extern→App
/// umgekehrt. Übernimmt außerdem frisch aus [PENDING_TCP_FDS] eingetroffene Sockets in die
/// [NatTable].
fn pump_established_sessions(
    sockets: &mut SocketSet<'static>,
    nat: &mut NatTable,
    flow_sockets: &mut HashMap<FlowKey, smoltcp::iface::SocketHandle>,
    stats: &StatsInner,
) {
    if let Ok(mut pending) = PENDING_TCP_FDS.lock() {
        for (key, fd) in pending.drain(..) {
            if flow_sockets.contains_key(&key) {
                nat.insert(
                    key,
                    NatSession {
                        external_fd: fd,
                        smoltcp_handle: 0,
                        last_active: Instant::now(),
                    },
                );
            } else {
                // Der zugehörige smoltcp-Socket wurde inzwischen geräumt (z. B. App hat die
                // Verbindung sofort wieder geschlossen) — der frisch beschaffte fd wäre verwaist;
                // sauber schließen statt leaken.
                unsafe {
                    let _ = TcpStream::from_raw_fd(fd);
                }
            }
        }
    }

    let mut to_touch = Vec::new();
    for (key, handle) in flow_sockets.iter() {
        let Some(session) = nat.get(key) else {
            continue; // externer Socket steht noch aus (s. o.) oder Session wurde geräumt.
        };
        let socket = sockets.get_mut::<tcp::Socket>(*handle);
        if !socket.is_open() {
            continue; // wird beim nächsten Housekeeping-Tick über evict_idle/close_session geräumt.
        }

        // App -> extern.
        if socket.may_recv() {
            let mut buf = [0u8; TCP_BUFFER_SIZE];
            if let Ok(n) = socket.recv_slice(&mut buf)
                && n > 0 {
                    let mut stream = ManuallyDrop::new(unsafe { TcpStream::from_raw_fd(session.external_fd) });
                    let _ = stream.write_all(&buf[..n]);
                    stats.forwarded_bytes.fetch_add(n as u64, Ordering::SeqCst);
                    to_touch.push(key.clone());
                }
        }
        // Extern -> App.
        if socket.can_send() {
            let mut stream = ManuallyDrop::new(unsafe { TcpStream::from_raw_fd(session.external_fd) });
            let _ = stream.set_nonblocking(true);
            let mut buf = [0u8; TCP_BUFFER_SIZE];
            match stream.read(&mut buf) {
                Ok(0) => {
                    socket.close();
                }
                Ok(n) => {
                    let _ = socket.send_slice(&buf[..n]);
                    stats.forwarded_bytes.fetch_add(n as u64, Ordering::SeqCst);
                    to_touch.push(key.clone());
                }
                Err(e) if e.kind() == ErrorKind::WouldBlock => {}
                Err(_) => socket.close(),
            }
        }
    }
    let now = Instant::now();
    for key in to_touch {
        nat.touch(&key, now);
    }
}

/// UDP ist verbindungslos: ein einzelner gebundener smoltcp-Socket pro Port bedient beliebig viele
/// Gegenstellen über die pro Datagramm mitgelieferte Absenderadresse — kein Ersatz-Listener-Bedarf
/// wie bei TCP (s. Moduldoc). Jede noch nicht bekannte Absenderadresse bekommt sofort synchron
/// einen externen Socket (UDP-`connect()`/`sendto()` blockiert praktisch nie, anders als TCP-
/// Handshake — daher hier bewusst kein eigener Thread wie bei TCP).
fn pump_udp_listeners(
    sockets: &mut SocketSet<'static>,
    udp_listeners: &HashMap<u16, smoltcp::iface::SocketHandle>,
    nat: &mut NatTable,
    socket_factory: &Arc<dyn ProtectedSocketFactory>,
    flow_sockets: &mut HashMap<FlowKey, smoltcp::iface::SocketHandle>,
) {
    for (&port, &handle) in udp_listeners.iter() {
        let socket = sockets.get_mut::<udp::Socket>(handle);
        while let Ok((data, meta)) = socket.recv() {
            let key = FlowKey {
                proto: Proto::Udp,
                src_ip: meta.endpoint.addr.to_string(),
                src_port: meta.endpoint.port,
                dst_ip: String::new(), // bei UDP nicht zur Identifikation nötig (ein Socket pro lokalem Port).
                dst_port: port,
            };
            flow_sockets.entry(key.clone()).or_insert(handle);
            let external_fd = match nat.get(&key) {
                Some(session) => Some(session.external_fd),
                None => socket_factory.open_udp(meta.endpoint.addr.to_string(), port).ok(),
            };
            if let Some(fd) = external_fd {
                let stream = ManuallyDrop::new(unsafe { UdpSocket::from_raw_fd(fd) });
                let _ = stream.send(data);
                if nat.get(&key).is_none() {
                    nat.insert(
                        key,
                        NatSession {
                            external_fd: fd,
                            smoltcp_handle: 0,
                            last_active: Instant::now(),
                        },
                    );
                } else {
                    nat.touch(&key, Instant::now());
                }
            }
        }
    }
}

/// Extern→App-Richtung für UDP-Sessions: liest nicht-blockierend, was auf dem externen Socket
/// einer Session eingetroffen ist, und speist es in den passenden smoltcp-UDP-Socket zurück,
/// adressiert an die ursprüngliche App-Endpunktadresse (`FlowKey.src_*`) — die eigentliche
/// Antwort an die App übernimmt danach `iface.poll()`/das TUN-Schreiben wie gewohnt.
fn pump_udp_responses(
    sockets: &mut SocketSet<'static>,
    nat: &NatTable,
    flow_sockets: &HashMap<FlowKey, smoltcp::iface::SocketHandle>,
    stats: &StatsInner,
) {
    for (key, session) in nat.iter() {
        if !matches!(key.proto, Proto::Udp) {
            continue;
        }
        let Some(&handle) = flow_sockets.get(key) else {
            continue;
        };
        let Ok(src_addr) = Ipv4Address::from_str(&key.src_ip) else {
            continue;
        };
        let endpoint = smoltcp::wire::IpEndpoint::new(IpAddress::Ipv4(src_addr), key.src_port);

        let stream = ManuallyDrop::new(unsafe { UdpSocket::from_raw_fd(session.external_fd) });
        let _ = stream.set_nonblocking(true);
        let mut buf = [0u8; UDP_BUFFER_SIZE];
        loop {
            match stream.recv(&mut buf) {
                Ok(n) if n > 0 => {
                    let socket = sockets.get_mut::<udp::Socket>(handle);
                    let _ = socket.send_slice(&buf[..n], endpoint);
                    stats.forwarded_bytes.fetch_add(n as u64, Ordering::SeqCst);
                }
                Ok(_) => break,
                Err(e) if e.kind() == ErrorKind::WouldBlock => break,
                Err(_) => break,
            }
        }
    }
}

fn close_session(
    key: &FlowKey,
    session: NatSession,
    sockets: &mut SocketSet<'static>,
    flow_sockets: &mut HashMap<FlowKey, smoltcp::iface::SocketHandle>,
) {
    if let Some(handle) = flow_sockets.remove(key) {
        match key.proto {
            Proto::Tcp => {
                let socket = sockets.get_mut::<tcp::Socket>(handle);
                socket.abort();
                sockets.remove(handle);
            }
            Proto::Udp => {
                // UDP-Listener-Sockets sind dauerhaft (ein Socket pro Port bedient mehrere Peers)
                // — hier nur den Flow-Eintrag entfernen, nicht den Socket selbst.
            }
        }
    }
    unsafe {
        // Externen Socket schließen: Ownership ging beim Callback-Rückgabewert an Rust über (s.
        // `callback.rs`-Klassendoc) — anders als der Tun-fd wird dieser hier real geschlossen.
        let _ = File::from_raw_fd(session.external_fd);
    }
}
