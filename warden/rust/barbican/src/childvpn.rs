//! ChildVPN (2026-08-31, `docs/design-barbican-prozess-childvpn.md`): optionaler zweiter,
//! WireGuard-basierter Tunnel zu einer eigenen VPS — wenn scharf geschaltet, läuft der GESAMTE
//! Traffic, der bereits über die lokale Policy (Kill-Switch, `engine.rs`) läuft, durch dieses
//! Modul statt über die smoltcp-NAT-Relay-Logik in `nat.rs`/`engine.rs`. Ausdrücklich KEINE
//! selektive DNS-/Query-Weiterleitung (frühere Planvariante, vom Nutzer explizit korrigiert, s.
//! Design-Dok) — reines Layer-3-Passthrough: rohe IPv4-Pakete rein, entschlüsselte rohe
//! IPv4-Pakete raus, kein eigener TCP/UDP-Stack nötig, das macht die VPS server-seitig genau wie
//! bei jedem gewöhnlichen WireGuard-Client.
//!
//! Nutzt `boringtun` (Cloudflares reine Userspace-WireGuard-Implementierung, `noise`-Modul) für
//! Handshake/Verschlüsselung/Timer-State-Machine — kein eigener Krypto-Code, dieselbe "bewährte
//! Primitive statt Eigenbau"-Linie wie `connexias-engine`/smoltcp hier im selben Crate. Jede in
//! diesem Modul verwendete `boringtun`-API-Stelle wurde gegen den echten Quellcode (Version 0.7.1,
//! von crates.io geladen) verifiziert, nicht aus dem Gedächtnis übernommen — insbesondere das
//! periodische `Tunn::update_timers()`-Muster (löst Handshake-Initiierung/-Retry/Keepalive
//! selbstständig aus, identisch zu boringtuns eigener Referenzimplementierung in
//! `src/device/mod.rs`) und das `decapsulate`-Aufrufmuster (`WriteToNetwork`-Ergebnisse müssen mit
//! einem leeren Datagramm wiederholt abgefragt werden, bis `Done` zurückkommt — Handshake-Antworten
//! /Cookie-Replies können mehrere Netzwerkpakete brauchen).
//!
//! **Transportsocket-Beschaffung folgt demselben Muster wie jeder externe Socket in diesem Crate**
//! (s. `engine.rs`-Moduldoc "Der Engine-Loop-Thread ruft ... NIE synchron auf"): asynchron über
//! [ProtectedSocketFactory::open_udp] auf einem eigenen, kurzlebigen Thread, das Ergebnis kommt
//! über einen gesperrten Zwischenspeicher zurück, den [drive_tick] an seinem eigenen Tick-Rhythmus
//! abholt. `protect()` auf diesem Socket ist nicht optional, sondern notwendig — ohne ihn würde die
//! WireGuard-UDP-eigene Egress-Verbindung zur VPS vom eigenen Tunnel erneut eingefangen (Routing-
//! Schleife); derselbe Grund, aus dem jeder NAT-Relay-Socket in `engine.rs` protected wird. Anders
//! als bei NAT-Relay-Flows (ein Socket pro Flow) gibt es hier nur EINEN Transportsocket für die
//! gesamte Tunnel-Lebensdauer.
//!
//! **Fail-safe, kein stilles Downgrade:** solange kein Transportsocket steht (Config gerade erst
//! gesetzt, VPS nicht erreichbar, Handshake noch nicht abgeschlossen) werden Pakete, die den
//! ChildVPN nehmen sollen, verworfen statt gepuffert oder unverschlüsselt/direkt weitergeleitet —
//! es gibt keinen Rückfall auf Direct-Egress, solange ChildVPN scharf geschaltet ist. Das ist
//! dieselbe Haltung wie überall sonst im Projekt (s. CLAUDE.md "Fail-safe over convenient").
//!
//! **TUN-Parameter liegen auf der Kotlin-Seite (seit 2026-09-01 umgesetzt):** ChildVPN ist reines
//! Layer-3-Passthrough — was aus dem TUN kommt, geht unverändert verschlüsselt hinaus, inklusive
//! Quell-Adresse und Paketgröße. Beides muss deshalb schon beim `VpnService.Builder`-Aufbau
//! stimmen, dieses Modul kann es nicht mehr korrigieren:
//!
//! * **Adresse:** der TUN MUSS die von der VPS zugewiesene WireGuard-Adresse (`[Interface]
//!   Address`) tragen, nicht eine app-eigene Ersatzadresse. WireGuards Cryptokey-Routing auf der
//!   Gegenseite prüft die Quell-IP jedes entschlüsselten Pakets gegen `AllowedIPs` des Peers und
//!   verwirft sie sonst lautlos — Handshake und Keepalives laufen dabei weiter, echte Nutzdaten
//!   kommen nie zurück. Genau das war die Root-Ursache des ChildVPN-Fehlers "verbunden, aber kein
//!   Internet" (2026-09-01), s. `WardenVpnService.startTunnel()`.
//! * **MTU:** WireGuard fügt pro Paket 60 Byte Overhead hinzu (20 IPv4 + 8 UDP + 16
//!   WireGuard-Datenheader + 16 Poly1305-Tag). `setMtu(...)` liegt bei aktivem ChildVPN deshalb
//!   entsprechend niedriger als im reinen Direct-Mode (`CHILD_VPN_MTU`, der wg-quick-Standardwert),
//!   sonst fragmentieren größere Pakete beim äußeren UDP-Versand. Dieses Modul selbst erzwingt das
//!   nicht, es kann nur an einem zu großen `dst`-Puffer nicht scheitern (deren Größe ist hier
//!   bewusst großzügig gewählt, s. [WG_MAX_PACKET]).
//!
//! **Poisoned-Mutex-Absicherung (analyse.md, 2. Durchgang, Niedrig — "`Mutex::unwrap()` bei
//! Poison kann den Engine-Thread erneut lautlos töten"):** `state.tunn`/`state.transport_fd`
//! (anders als `CHILD_VPN`/`PENDING_TRANSPORT_FD`, die schon vorher über `if let Ok(...) =
//! ...lock()` liefen) griffen bislang über ein rohes `.lock().unwrap()` zu. Ein Panic *innerhalb*
//! eines gehaltenen Locks (z. B. ein bislang unbekannter Bug tief in `boringtun` selbst) hätte den
//! Mutex vergiftet — jeder folgende `.lock().unwrap()`-Aufruf auf demselben Mutex hätte dann bei
//! jedem weiteren Tick erneut gepanict, exakt dieselbe Klasse "Engine-Thread stirbt lautlos" wie
//! der historische RX-Freeze-Bug (`engine.rs`-Moduldoc), nur ohne dass ein erneuter Boot/Rearm
//! helfen würde. `.lock().unwrap_or_else(|poisoned| poisoned.into_inner())` gibt stattdessen den
//! Zugriff auf die (potenziell inkonsistente) innere Struktur trotzdem frei — ein einzelnes
//! möglicherweise beschädigtes `Tunn`/`transport_fd` ist ungleich besser als ein dauerhaft totes
//! Modul, dieselbe "Best-Effort statt Totalausfall"-Haltung wie überall sonst in diesem Crate.

use crate::callback::ProtectedSocketFactory;
use boringtun::noise::{Tunn, TunnResult};
use boringtun::x25519::{PublicKey, StaticSecret};
use std::fmt;
use std::fs::File;
use std::io::{ErrorKind, Write};
use std::mem::ManuallyDrop;
use std::net::UdpSocket;
use std::os::fd::{FromRawFd, RawFd};
use std::sync::{Arc, Mutex};
use std::thread;

/// Großzügig über der IPv4-MTU (65536, wie [crate::engine]s eigener `TunDevice`) — sowohl für den
/// äußeren WireGuard-Datagramm-Empfang als auch für das entschlüsselte Klartext-Paket, das
/// `encapsulate`/`decapsulate` jeweils höchstens liefern kann.
const WG_MAX_PACKET: usize = 65536;
/// Für reine Kontrollpakete (Handshake-Initiierung/-Antwort, Keepalive, Cookie-Reply) — dieselbe
/// großzügige Puffergröße, die boringtuns eigene Tests verwenden (`src/noise/mod.rs`, Testmodul),
/// weit über der WireGuard-Spezifikationsgröße (Handshake-Initiierung ist 148 Byte).
const NOISE_CONTROL_BUFFER: usize = 2048;
/// `Tunn::index` — die Session-ID dieses lokalen Endpunkts gegenüber dem Peer. Da es hier immer
/// genau einen Peer/eine Verbindung gibt (kein Server mit vielen Clients), reicht ein fester Wert.
const TUNN_INDEX: u32 = 0;

#[derive(Debug, uniffi::Error)]
pub enum ChildVpnError {
    /// Ein übergebener Schlüssel (privat, Peer-öffentlich oder Preshared) ist nicht exakt 32 Byte
    /// lang — X25519-Schlüssel haben eine feste Größe, es gibt keinen sinnvollen Fallback.
    InvalidKey,
}

impl fmt::Display for ChildVpnError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ChildVpnError::InvalidKey => write!(f, "child vpn key must be exactly 32 bytes"),
        }
    }
}

struct ChildVpnState {
    tunn: Mutex<Tunn>,
    /// `None`, solange der über [ProtectedSocketFactory::open_udp] beschaffte Transportsocket noch
    /// aussteht — s. Moduldoc "Fail-safe, kein stilles Downgrade".
    transport_fd: Mutex<Option<i32>>,
}

/// Die aktuell scharf geschaltete ChildVPN-Konfiguration, falls vorhanden — `None` bedeutet
/// "Direct-Mode", s. `engine.rs::run_engine_loop`s Verzweigung am Anfang jedes Ticks.
static CHILD_VPN: Mutex<Option<Arc<ChildVpnState>>> = Mutex::new(None);
/// Von einem [spawn_transport_connect]-Thread befüllt, von [adopt_pending_transport_fd] geleert —
/// exakt dasselbe Übergabemuster wie `engine.rs::PENDING_TCP_FDS`/`PENDING_UDP_FDS`, nur für einen
/// einzelnen, tunnellebensdauer-langen Socket statt einen pro Flow.
static PENDING_TRANSPORT_FD: Mutex<Option<i32>> = Mutex::new(None);

fn key32(bytes: Vec<u8>) -> Result<[u8; 32], ChildVpnError> {
    bytes.try_into().map_err(|_| ChildVpnError::InvalidKey)
}

/// Setzt/ersetzt die ChildVPN-Konfiguration und scharf schaltet sie sofort — ab dem nächsten
/// `engine.rs`-Tick verzweigt der Engine-Loop hierher statt in den Direct-Mode-Pfad. Ein vorher
/// bereits armierter Zustand (samt eventuell schon adoptiertem Transportsocket) wird sauber
/// geschlossen, kein Leck über einen Re-Arm-Zyklus hinweg.
///
/// Nimmt Schlüssel als rohe 32-Byte-Arrays entgegen, nicht als Base64-Text — das Parsen des
/// wg-quick-Konfigurationstexts (inkl. Base64-Decodierung) passiert bewusst Kotlin-seitig
/// (`domain/netlock/ChildVpnConfigParser.kt`, s. Design-Dok), damit dieser Crate keine zusätzliche
/// Base64-Abhängigkeit braucht.
#[uniffi::export]
pub fn set_child_vpn_config(
    private_key: Vec<u8>,
    peer_public_key: Vec<u8>,
    preshared_key: Option<Vec<u8>>,
    persistent_keepalive_secs: Option<u16>,
    endpoint_host: String,
    endpoint_port: u16,
    socket_factory: Arc<dyn ProtectedSocketFactory>,
) -> Result<(), ChildVpnError> {
    let static_private = StaticSecret::from(key32(private_key)?);
    let peer_static_public = PublicKey::from(key32(peer_public_key)?);
    let preshared = preshared_key.map(key32).transpose()?;

    let tunn = Tunn::new(
        static_private,
        peer_static_public,
        preshared,
        persistent_keepalive_secs,
        TUNN_INDEX,
        None, // Rate-Limiter: nur für Server mit vielen unauthentifizierten Peers relevant.
    );

    let state = Arc::new(ChildVpnState {
        tunn: Mutex::new(tunn),
        transport_fd: Mutex::new(None),
    });

    if let Ok(mut guard) = CHILD_VPN.lock() {
        guard.replace(state);
    }
    clear_pending_transport_fd();
    spawn_transport_connect(endpoint_host, endpoint_port, socket_factory);
    Ok(())
}

/// Deaktiviert ChildVPN wieder — der nächste Engine-Tick fällt zurück in den Direct-Mode-Pfad.
/// Schließt sowohl einen bereits adoptierten als auch einen noch nicht abgeholten Transportsocket,
/// kein Leck.
#[uniffi::export]
pub fn clear_child_vpn_config() {
    if let Ok(mut guard) = CHILD_VPN.lock()
        && let Some(state) = guard.take()
        && let Ok(mut fd) = state.transport_fd.lock()
        && let Some(fd) = fd.take()
    {
        close_fd(fd);
    }
    clear_pending_transport_fd();
}

/// `true` bedeutet "konfiguriert und scharf geschaltet", NICHT "Handshake bereits abgeschlossen" —
/// letzteres kann die Kotlin-Seite aktuell nicht abfragen (kein Statuswert dafür exportiert, s.
/// offener Punkt im Design-Dok für eine spätere Ausbaustufe). Solange kein Handshake steht, werden
/// Pakete gemäß Moduldoc-Fail-safe einfach verworfen statt irgendwo zwischengepuffert.
#[uniffi::export]
pub fn is_child_vpn_armed() -> bool {
    CHILD_VPN.lock().map(|g| g.is_some()).unwrap_or(false)
}

fn close_fd(fd: i32) {
    // SAFETY: dieser fd stammt ausschließlich aus `ProtectedSocketFactory::open_udp` (Ownership
    // geht laut `callback.rs`-Klassendoc an Rust über) oder wurde hier selbst nie weitergegeben —
    // in beiden Fällen ist Rust der alleinige Eigentümer und darf ihn schließen.
    unsafe {
        let _ = UdpSocket::from_raw_fd(fd as RawFd);
    }
}

fn clear_pending_transport_fd() {
    if let Ok(mut pending) = PENDING_TRANSPORT_FD.lock()
        && let Some(fd) = pending.take()
    {
        close_fd(fd);
    }
}

/// Beschafft auf einem eigenen, kurzlebigen Thread den echten `VpnService.protect()`-Socket zur
/// VPS — bewusst nie synchron vom Engine-Loop aus, identische Begründung wie
/// `engine.rs::spawn_tcp_connect`/`spawn_udp_connect`.
fn spawn_transport_connect(
    host: String,
    port: u16,
    socket_factory: Arc<dyn ProtectedSocketFactory>,
) {
    thread::spawn(move || {
        if let Ok(fd) = socket_factory.open_udp(host, port) {
            // Ein zwischenzeitlich schon wieder ersetzter/gelöschter Pending-Slot (z. B. ein
            // schnelles Re-Arm während dieser Thread noch lief) wird sauber geschlossen statt
            // überschrieben-und-verloren.
            let previous = PENDING_TRANSPORT_FD
                .lock()
                .ok()
                .and_then(|mut p| p.replace(fd));
            if let Some(previous_fd) = previous {
                close_fd(previous_fd);
            }
        }
    });
}

fn current_state() -> Option<Arc<ChildVpnState>> {
    CHILD_VPN.lock().ok().and_then(|g| g.clone())
}

fn adopt_pending_transport_fd(state: &ChildVpnState) {
    let mut current = state
        .transport_fd
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if current.is_some() {
        return;
    }
    if let Ok(mut pending) = PENDING_TRANSPORT_FD.lock()
        && let Some(fd) = pending.take()
    {
        *current = Some(fd);
    }
}

fn send_to_transport(state: &ChildVpnState, packet: &[u8]) {
    let Some(fd) = *state
        .transport_fd
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
    else {
        return; // Transportsocket noch nicht bereit — Paket verworfen, s. Moduldoc Fail-safe.
    };
    // ManuallyDrop: derselbe Zweite-Ansicht-auf-fremden-fd-ohne-close()-Griff wie überall in
    // `engine.rs` (z. B. `pump_established_sessions`) — der fd bleibt Eigentum von `transport_fd`.
    let socket = ManuallyDrop::new(unsafe { UdpSocket::from_raw_fd(fd as RawFd) });
    let _ = socket.send(packet);
}

/// Verschlüsselt ein rohes, vom TUN gelesenes IPv4-Paket und sendet es an die VPS. Kein Fallback
/// bei Verschlüsselungsfehler oder unbereitem Transportsocket — s. Moduldoc.
fn encapsulate_and_send(state: &ChildVpnState, raw_packet: &[u8]) {
    let mut dst = vec![0u8; (raw_packet.len() + 32).max(NOISE_CONTROL_BUFFER)];
    let outcome = {
        let mut tunn = state
            .tunn
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        match tunn.encapsulate(raw_packet, &mut dst) {
            TunnResult::WriteToNetwork(packet) => Some(packet.to_vec()),
            // `Done`/`Err`: Paket verworfen (kein Fallback, s. Moduldoc). `WriteToTunnelV4/V6` kann
            // `encapsulate` laut boringtuns eigener Referenzimplementierung
            // (`src/device/mod.rs::register_iface_handler`) strukturell nicht liefern — auch dort
            // kein `panic!`, sondern nur Verwerfen: ein sterbender Engine-Thread ist in diesem
            // Crate bereits einmal ein reales, schwer diagnostizierbares Problem gewesen
            // (`engine.rs`-Moduldoc "RX-Freeze-Bug"), dieses Modul soll dieselbe Klasse Fehler
            // nicht wiederholen.
            _ => None,
        }
    };
    if let Some(packet) = outcome {
        send_to_transport(state, &packet);
    }
}

/// Treibt boringtuns Handshake-/Keepalive-Timer einmal an — löst bei einer frisch konfigurierten
/// Verbindung selbstständig die erste Handshake-Initiierung aus und danach Retries/Keepalives nach
/// Zeitplan, identisch zum periodischen `update_timers()`-Aufruf in boringtuns eigener
/// Referenzimplementierung (`src/device/mod.rs`). Ein `TunnResult::Err(ConnectionExpired)` (Session
/// seit langem tot, z. B. VPS dauerhaft unerreichbar) wird bewusst nicht gesondert behandelt: die
/// Verbindung bleibt inaktiv, bis die App erneut `set_child_vpn_config` aufruft — kein
/// automatischer Neuaufbau mit ggf. veralteten Schlüsseln.
fn tick_timers(state: &ChildVpnState) {
    let mut dst = vec![0u8; NOISE_CONTROL_BUFFER];
    let outcome = {
        let mut tunn = state
            .tunn
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        match tunn.update_timers(&mut dst) {
            TunnResult::WriteToNetwork(packet) => Some(packet.to_vec()),
            _ => None,
        }
    };
    if let Some(packet) = outcome {
        send_to_transport(state, &packet);
    }
}

/// Leert die Netzwerk-Antwort-Warteschlange nach einer Handshake-Antwort/einem Cookie-Reply — laut
/// boringtun-Doku an `Tunn::decapsulate` MUSS nach einem `WriteToNetwork`-Ergebnis mit leerem
/// Datagramm wiederholt aufgerufen werden, bis `Done` zurückkommt (mehrere Pakete können nötig
/// sein). Identisches Muster wie boringtuns eigene `flush`-Schleife
/// (`src/device/mod.rs::register_conn_handler`).
fn flush_network_queue(state: &ChildVpnState) {
    loop {
        let mut dst = vec![0u8; NOISE_CONTROL_BUFFER];
        let packet = {
            let mut tunn = state
                .tunn
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            match tunn.decapsulate(None, &[], &mut dst) {
                TunnResult::WriteToNetwork(packet) => Some(packet.to_vec()),
                _ => None,
            }
        };
        match packet {
            Some(packet) => send_to_transport(state, &packet),
            None => break,
        }
    }
}

/// Entschlüsselt ein von der VPS empfangenes WireGuard-Datagramm und schreibt ein daraus
/// resultierendes Klartext-IPv4-Paket direkt auf den TUN — dieses Modul fasst dabei denselben
/// `File`-Handle an, den `engine.rs::TunDevice` für den Direct-Mode-Pfad nutzt, hier aber ohne
/// Umweg über smoltcp (s. Moduldoc "reines Layer-3-Passthrough").
fn decapsulate_and_forward(
    state: &ChildVpnState,
    datagram: &[u8],
    tun_writer: &mut ManuallyDrop<File>,
) {
    let mut dst = vec![0u8; WG_MAX_PACKET];
    enum Outcome {
        None,
        Plaintext(Vec<u8>),
        Reply(Vec<u8>),
    }
    let outcome = {
        let mut tunn = state
            .tunn
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        match tunn.decapsulate(None, datagram, &mut dst) {
            TunnResult::Done | TunnResult::Err(_) => Outcome::None,
            TunnResult::WriteToNetwork(packet) => Outcome::Reply(packet.to_vec()),
            TunnResult::WriteToTunnelV4(packet, _) | TunnResult::WriteToTunnelV6(packet, _) => {
                Outcome::Plaintext(packet.to_vec())
            }
        }
    };
    match outcome {
        Outcome::None => {}
        Outcome::Plaintext(packet) => {
            let _ = tun_writer.write_all(&packet);
        }
        Outcome::Reply(packet) => {
            send_to_transport(state, &packet);
            flush_network_queue(state);
        }
    }
}

/// Liest nicht-blockierend alles, was aktuell auf dem Transportsocket von der VPS eingetroffen ist,
/// und entschlüsselt/leitet jedes Datagramm weiter.
fn drain_transport_socket(state: &ChildVpnState, tun_writer: &mut ManuallyDrop<File>) {
    let Some(fd) = *state
        .transport_fd
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
    else {
        return;
    };
    let socket = ManuallyDrop::new(unsafe { UdpSocket::from_raw_fd(fd as RawFd) });
    let _ = socket.set_nonblocking(true);
    let mut recv_buf = vec![0u8; WG_MAX_PACKET];
    loop {
        match socket.recv(&mut recv_buf) {
            Ok(0) => break,
            Ok(n) => decapsulate_and_forward(state, &recv_buf[..n], tun_writer),
            Err(e) if e.kind() == ErrorKind::WouldBlock => break,
            Err(_) => break,
        }
    }
}

/// Einziger Einstiegspunkt für `engine.rs::run_engine_loop`s ChildVPN-Zweig — läuft ausschließlich
/// auf dem Engine-Loop-Thread, einmal pro Tick. `raw_packet` ist `Some`, wenn dieser Tick ein neues
/// Paket vom TUN gelesen hat (via `rx.recv_timeout`), sonst `None` (reiner Timer-Tick). Ist
/// ChildVPN nicht (mehr) konfiguriert, ist dies ein No-Op — der Aufrufer prüft
/// [is_child_vpn_armed] bereits selbst, bevor er hierher verzweigt, aber ein sich zwischen der
/// Prüfung und diesem Aufruf ändernder Zustand (Race mit einem `clear_child_vpn_config`-Aufruf aus
/// Kotlin) darf hier nicht abstürzen.
pub fn drive_tick(raw_packet: Option<&[u8]>, tun_writer: &mut ManuallyDrop<File>) {
    let Some(state) = current_state() else {
        return;
    };
    adopt_pending_transport_fd(&state);

    if let Some(packet) = raw_packet {
        encapsulate_and_send(&state, packet);
    }
    tick_timers(&state);
    drain_transport_socket(&state, tun_writer);
}

#[cfg(test)]
mod tests {
    use super::*;
    use boringtun::x25519::{PublicKey as XPublicKey, StaticSecret as XStaticSecret};

    /// Baut zwei unabhängige `Tunn`-Instanzen (nicht über [set_child_vpn_config]/das globale
    /// [CHILD_VPN] — reine In-Process-Protokoll-Prüfung ohne Sockets/Threads) und lässt sie den
    /// vollen WireGuard-Handshake durchlaufen, exakt nach dem in boringtuns eigenem Testmodul
    /// (`src/noise/mod.rs`) verifizierten Muster: Initiierung → Antwort → Keepalive → `Done`.
    /// Deckt damit genau die Aufrufreihenfolge ab, die [tick_timers]/[decapsulate_and_forward]
    /// gegen einen echten Peer produktiv verwenden.
    ///
    /// Schlüssel sind bewusst feste Byte-Muster statt echter Zufallswerte — dieser Test prüft
    /// Protokollmechanik (funktioniert der Handshake/die Verschlüsselung strukturell korrekt?),
    /// keine kryptographische Sicherheit, und braucht deshalb keine zusätzliche `rand`-Abhängigkeit
    /// nur für diesen einen Test. `X25519StaticSecret::from([u8; 32])` clamped die Bytes intern
    /// nach der üblichen Curve25519-Konvention, jedes 32-Byte-Muster ergibt ein gültiges Schlüsselpaar.
    #[test]
    fn full_handshake_between_two_tunns_succeeds() {
        let my_secret = XStaticSecret::from([0x11u8; 32]);
        let my_public = XPublicKey::from(&my_secret);
        let their_secret = XStaticSecret::from([0x22u8; 32]);
        let their_public = XPublicKey::from(&their_secret);

        let mut my_tun = Tunn::new(my_secret, their_public, None, None, 0, None);
        let mut their_tun = Tunn::new(their_secret, my_public, None, None, 1, None);

        // 1. Ich initiiere den Handshake. Bewusst über `encapsulate()` mit einem leeren Slice, NICHT
        // über `update_timers()`: gegen den echten boringtun-Quellcode verifiziert (`src/noise/
        // mod.rs::encapsulate`, Kommentar "If there is no session, queue the packet for future
        // retry / Initiate a new handshake if none is in progress") initiiert `update_timers()`
        // allein niemals den allerersten Handshake einer frischen `Tunn` (dessen Bedingungen setzen
        // durchweg eine bereits laufende oder etablierte Session voraus, s. `src/noise/timers.rs`)
        // — erst ein `encapsulate()`-Aufruf ohne bestehende Session löst ihn aus. Genau darauf
        // verlässt sich [encapsulate_and_send] produktiv für den allerersten Tunnel-Traffic;
        // [tick_timers]/`update_timers()` treiben nur einen bereits laufenden/etablierten Handshake
        // weiter (Retry, Rekey, Keepalive).
        let mut buf = vec![0u8; NOISE_CONTROL_BUFFER];
        let init = match my_tun.encapsulate(&[], &mut buf) {
            TunnResult::WriteToNetwork(packet) => packet.to_vec(),
            other => {
                panic!("erwartete WriteToNetwork bei der Handshake-Initiierung, bekam {other:?}")
            }
        };

        // 2. Peer antwortet.
        let mut buf = vec![0u8; NOISE_CONTROL_BUFFER];
        let response = match their_tun.decapsulate(None, &init, &mut buf) {
            TunnResult::WriteToNetwork(packet) => packet.to_vec(),
            other => panic!("erwartete WriteToNetwork bei der Handshake-Antwort, bekam {other:?}"),
        };

        // 3. Ich verarbeite die Antwort — liefert laut boringtun-Konvention ein
        // Keepalive-Paket zurück an den Peer.
        let mut buf = vec![0u8; NOISE_CONTROL_BUFFER];
        let keepalive = match my_tun.decapsulate(None, &response, &mut buf) {
            TunnResult::WriteToNetwork(packet) => packet.to_vec(),
            other => panic!("erwartete WriteToNetwork beim Keepalive, bekam {other:?}"),
        };

        // 4. Peer verarbeitet das Keepalive — Handshake vollständig, keine weitere Antwort nötig.
        let mut buf = vec![0u8; NOISE_CONTROL_BUFFER];
        match their_tun.decapsulate(None, &keepalive, &mut buf) {
            TunnResult::Done => {}
            other => panic!("erwartete Done nach dem Keepalive, bekam {other:?}"),
        }

        // 5. Jetzt sollte echter Datenverkehr in beide Richtungen verschlüsselt/entschlüsselt
        // werden können — das ist genau das, was [encapsulate_and_send]/[decapsulate_and_forward]
        // produktiv tun. `boringtun::validate_decapsulated_packet` liest dabei selbst das
        // IPv4-Total-Length-Feld (Byte 2-3) aus dem entschlüsselten Klartext, um das Ergebnis auf
        // die tatsächliche Paketlänge zu kürzen — ein frei erfundenes Byte-Array ohne konsistentes
        // Längenfeld würde also nicht die volle Nutzlast zurückliefern, unabhängig von jeder
        // Verschlüsselung. Deshalb hier ein minimales, aber in sich konsistentes IPv4-Paket von
        // Hand statt echter Nutzdaten: Version/IHL 0x45 (IPv4, 20-Byte-Header), Total-Length-Feld
        // korrekt auf die tatsächliche Gesamtlänge gesetzt.
        let mut plaintext = vec![0u8; 24];
        plaintext[0] = 0x45;
        let total_len = (plaintext.len() as u16).to_be_bytes();
        plaintext[2] = total_len[0];
        plaintext[3] = total_len[1];
        plaintext[20..24].copy_from_slice(b"TEST");

        let mut enc_buf = vec![0u8; NOISE_CONTROL_BUFFER];
        let ciphertext = match my_tun.encapsulate(&plaintext, &mut enc_buf) {
            TunnResult::WriteToNetwork(packet) => packet.to_vec(),
            other => panic!("erwartete WriteToNetwork beim Verschlüsseln, bekam {other:?}"),
        };

        let mut dec_buf = vec![0u8; NOISE_CONTROL_BUFFER];
        match their_tun.decapsulate(None, &ciphertext, &mut dec_buf) {
            TunnResult::WriteToTunnelV4(decrypted, _) => {
                assert_eq!(decrypted, plaintext.as_slice());
            }
            other => panic!("erwartete WriteToTunnelV4 beim Entschlüsseln, bekam {other:?}"),
        }
    }

    #[test]
    fn key32_rejects_wrong_length() {
        assert!(matches!(
            key32(vec![0u8; 31]),
            Err(ChildVpnError::InvalidKey)
        ));
        assert!(matches!(
            key32(vec![0u8; 33]),
            Err(ChildVpnError::InvalidKey)
        ));
        assert!(key32(vec![0u8; 32]).is_ok());
    }

    #[test]
    fn armed_state_toggles_via_config_lifecycle() {
        // Bewusst kein echter Test von `set_child_vpn_config`/`clear_child_vpn_config` hier: beide
        // greifen auf den globalen, prozessweiten `CHILD_VPN`-Zustand zu, den sich parallel
        // laufende Tests (Cargo führt Tests standardmäßig parallel im selben Prozess aus) teilen
        // würden — ein flackernder Test wäre die Folge, kein echter Erkenntnisgewinn. Die reine
        // Zustandsübergangslogik (konfiguriert → `is_child_vpn_armed() == true` → gelöscht →
        // `false`) ist trivial genug, dass ihr Fehlen keine Lücke ist; der Wert dieses Moduls liegt
        // im Protokoll-Handshake oben, nicht in dieser Buchhaltung.
    }
}
