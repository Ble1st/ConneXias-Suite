# Phase 0: Barbican-Kernfehler-Analyse

**Version:** 1.0  
**Datum:** 2026-08-29  
**Status:** In Arbeit (Phase 0, Tag 1)  
**Verantwortlich:** Mistral Vibe  

---

## 📌 Zusammenfassung

Dieses Dokument analysiert den **Kernfehler** im geparkten Barbican-Code (`app/netlock-disabled/`), der verhindert, dass die DNS-Blockliste/NAT-Relay auf einem frisch aufgebauten Tunnel Traffic verarbeitet. Die Analyse basiert auf:

1. **README.md** im `netlock-disabled`-Verzeichnis
2. **Kotlin-Code** in `app/netlock-disabled/de/ble1st/warden/netlock/`
3. **Rust-Code** in `rust/barbican/src/`
4. **Commit-Historie** (insbesondere Commit `7252396`)

---

## 🔍 Identifizierte Fehler (bereits behoben in Commit 7252396)

### Fehler 1: Fehlende INTERNET-Permission ⭐⭐⭐⭐⭐
**Datei:** `AndroidManifest.xml`  
**Status:** Behoben in Commit 7252396  
**Beschreibung:**
Die Warden-App hatte keine `android.permission.INTERNET`-Deklaration. Ohne diese Permission kann `protect()` nicht funktionieren, da der Socket keine Netzwerkverbindung aufbauen kann.  

**Fix:**
```xml
<uses-permission android:name="android.permission.INTERNET" />
```

---

### Fehler 2: DNS-Server-Adresse = Tunnel-Adresse ⭐⭐⭐⭐⭐
**Datei:** `WardenVpnService.kt` (Zeilen 105-129)  
**Status:** Behoben in Commit 7252396  
**Beschreibung:**
Der DNS-Server (`addDnsServer`) hatte dieselbe IP-Adresse wie die Tunnel-Adresse (`addAddress`). Der Android-Kernel behandelt Pakete an eine Adresse, die dem Interface selbst zugewiesen ist, als "lokale Zustellung" — sie verlassen den lokalen IP-Stack nie in Richtung `tun_fd`, sondern werden intern zugestellt (und mangels eines lauschenden Sockets verworfen).

**Symptom:**
- Keine echten IPv4-Pakete erreichten die Engine
- Nur IPv6-Router-Solicitation-Rauschen kam an (`v4=0, v6=N` in Debug-Zählern)

**Fix:**
```kotlin
// VORHER (FEHLER):
.addAddress(TUNNEL_IPV4, 32)
.addDnsServer(TUNNEL_IPV4)  // ← FALSCH!

// NACHHER (KORREKT):
.addAddress(TUNNEL_IPV4, 32)
.addDnsServer(TUNNEL_DNS_IPV4)  // ← Verschiedene Adresse im selben /24
```

**Konstanten:**
```kotlin
const val TUNNEL_IPV4 = "10.64.0.1"      // Interface-Adresse
const val TUNNEL_DNS_IPV4 = "10.64.0.2"  // DNS-Server-Adresse
```

---

### Fehler 3: Nicht-blockierender TUN-fd ⭐⭐⭐⭐⭐
**Datei:** `WardenVpnService.kt` (Zeilen 119-129)  
**Status:** Behoben in Commit 7252396  
**Beschreibung:**
Ohne `setBlocking(true)` liefert Android den TUN-fd **non-blocking** aus. Die Rust-Engine (`engine.rs`) nutzt eine blockierende `file.read()`-Schleife. Ein sofortiges `WouldBlock` beim allerersten Aufruf (noch kein Paket wartet) tötet den Lese-Thread, bevor je ein Paket verarbeitet wird. `startCapturedTunnel()` meldet trotzdem `running=true` (der Thread wurde gestartet, stirbt aber sofort wieder).

**Symptom:**
- Tunnel (`tun0`) korrekt aufgebaut
- Kein Traffic wird verarbeitet
- Nicht mal NXDOMAIN-Fastpath-Antwort für blockgelistete Domains

**Fix:**
```kotlin
Builder()
    // ...
    .setBlocking(true)  // ← KRITISCH!
```

---

### Fehler 4: Deadlock in `stopTunnel()` ⭐⭐⭐⭐⭐
**Datei:** `WardenVpnService.kt` (Zeilen 154-172)  
**Status:** Behoben in Commit 7252396  
**Beschreibung:**
Die vorherige Reihenfolge (`stopCapturedTunnel()` → `tunInterface?.close()`) deadlockte garantiert:
1. `stopCapturedTunnel()` blockiert synchron, bis Rusts Lese-Thread beendet ist
2. Rusts Lese-Thread wacht aber erst auf, wenn der `tun_fd` **GESCHLOSSEN** wird (SAFETY-Kommentar in `engine.rs`)
3. Die schließende Zeile wurde nie erreicht, weil Zeile davor ewig auf genau dieses Schließen wartete

**Symptom:**
- Nach erstem `ACTION_STOP_TUNNEL`/`ACTION_RELOAD_TUNNEL` reagierte der `worker` (SingleThreadExecutor) auf KEINEN weiteren Aufruf mehr
- `tun0` erschien fälschlich weiter als aktiv

**Fix:**
```kotlin
private fun stopTunnel(releaseForeground: Boolean = true) {
    tunInterface?.close()  // ← ERST fd schließen
    tunInterface = null
    runCatching { BarbicanEngine.stopCapturedTunnel() }  // ← DANN Rust aufräumen
    if (releaseForeground) {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
```

---

### Fehler 5: `protect()` auf Rust-eigenen Threads ⭐⭐⭐⭐⭐
**Datei:** `WardenVpnService.kt` (Zeilen 182-246)  
**Status:** Behoben in Commit 7252396  
**Beschreibung:**
Drei unabhängige Probleme:

1. **Thread-Typ:** Der aufrufende Thread ist ein Rust-eigener OS-Thread (per `std::thread::spawn`), via UniFFI/JNA an die JVM angehängt — aber KEIN von der Android-Runtime erzeugter Thread. `protect()` hing auf einem solchen Thread dauerhaft fest.

2. **Socket-Konstruktor:** `java.net.Socket()`/`DatagramSocket()` legen ihren nativen fd nicht zuverlässig sofort an. `protect()` braucht aber einen bereits existierenden fd.

3. **Fehlende Permission:** Siehe Fehler 1 (INTERNET-Permission).

**Fix:**
```kotlin
// 1. Separater Executor für Socket-Operationen
private val socketOpsExecutor = Executors.newCachedThreadPool()

// 2. SocketChannel/DatagramChannel statt Socket/DatagramSocket
override fun openTcp(dstIp: String, dstPort: UShort): Int = openProtected(dstIp, dstPort) { ip, port ->
    val channel = SocketChannel.open()  // ← fd sofort angelegt
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

// 3. openProtected führt auf socketOpsExecutor aus
private fun openProtected(dstIp: String, dstPort: UShort, body: (String, Int) -> Int): Int =
    try {
        socketOpsExecutor.submit(Callable { body(dstIp, dstPort.toInt()) })
            .get(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
    } catch (e: Exception) {
        Log.w(TAG, "openTcp/openUdp($dstIp:$dstPort) fehlgeschlagen", e)
        throw BarbicanSocketException.Failed()
    }
```

---

## 🚨 **Kernfehler: Ungeklärter Traffic-Stillstand (Noch OFFEN!)**

**Beschreibung:**
Auch **nach allen obigen Fixes** (Commit 7252396) verarbeitet die DNS-Blockliste/NAT-Relay auf einem **frisch aufgebauten Tunnel keinen Traffic mehr**. Dies ist der **letzte unverstandene Fehler**, der zur Deaktivierung führte.

### Symptome (Live-Test auf physischem Gerät):
1. Tunnel (`tun0`) wird korrekt aufgebaut
2. `startCapturedTunnel()` kehrt mit `running=true` zurück
3. **ABER:** Kein Traffic wird verarbeitet (weder Test-Queries noch Chrome-Traffic)
4. Keine blockgelisteten Domains lösen NXDOMAIN aus
5. Rust-Engine-Lese-Thread scheint zu starten, aber verarbeitet keine Pakete

### Mögliche Ursachen (Hypothesen):

#### Hypothese 1: smoltcp-Routing-Problem ⭐⭐⭐⭐
**Datei:** `engine.rs` (Zeilen 388-397)  
**Beschreibung:**
Die smoltcp-`Interface`-Konfiguration setzt eine **Default-Route** mit `set_any_ip(true)`. Wenn die Routing-Tabelle nicht korrekt aufgebaut wird, könnten Pakete nicht an die Engine weitergeleitet werden.

**Code:**
```rust
let mut iface = Interface::new(config, &mut device, SmolInstant::from_millis(0));
let octets = tun_addr.octets();
let smol_addr = Ipv4Address::new(octets[0], octets[1], octets[2], octets[3]);
iface.update_ip_addrs(|addrs| {
    let _ = addrs.push(IpCidr::new(IpAddress::Ipv4(smol_addr), 32));
});
iface.set_any_ip(true);
let _ = iface.routes_mut().add_default_ipv4_route(smol_addr);
```

**Problem:**
- `add_default_ipv4_route(smol_addr)` fügt eine Route mit **Gateway = smol_addr** hinzu
- Aber `smol_addr` ist die **Tunnel-Adresse selbst** (`10.64.0.1`)
- Das könnte zu einer Routing-Schleife führen: Pakete werden an `10.64.0.1` gesendet, aber das ist das lokale Interface

**Fix-Vorschlag:**
```rust
// Statt:
let _ = iface.routes_mut().add_default_ipv4_route(smol_addr);

// Versuchen:
let _ = iface.routes_mut().add_default_ipv4_route(Ipv4Address::new(0, 0, 0, 0));
// Oder:
// Keine Default-Route setzen, sondern explizite Routes für die DNS-Sentinel
```

---

#### Hypothese 2: TUN-Device-Konfiguration ⭐⭐⭐⭐
**Datei:** `engine.rs` (Zeilen 305-363)  
**Beschreibung:**
Das `TunDevice` verwendet eine **VecDeque** als Puffer für eingehende Pakete. Wenn der Reader-Thread Pakete in die Warteschlange schiebt, aber der Engine-Thread sie nicht abholt, könnten Pakete verloren gehen.

**Code:**
```rust
struct TunDevice {
    inbound: std::collections::VecDeque<Vec<u8>>,
    writer: ManuallyDrop<File>,
}

impl Device for TunDevice {
    type RxToken<'a> = TunRxToken;
    type TxToken<'a> = TunTxToken;

    fn receive(&mut self, _timestamp: SmolInstant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let buf = self.inbound.pop_front()?;
        Some((TunRxToken { buf }, TunTxToken { writer: &mut self.writer }))
    }
    // ...
}
```

**Problem:**
- `run_engine_loop` (Zeile 411-449) ruft `rx.recv_timeout(ENGINE_TICK)` auf
- Wenn ein Paket empfangen wird, wird `try_fast_path_dns_reply` aufgerufen
- Dann wird `ensure_listener_for_packet` aufgerufen
- **ABER:** Nur wenn `try_fast_path_dns_reply` **keine** Antwort zurückgibt, wird das Paket mit `device.inbound.push_back(packet)` in die Warteschlange geschoben
- Wenn das Paket **kein DNS-Paket** ist (z. B. TCP-SYN), wird es **NICHT** in die Warteschlange geschoben!

**Fix-Vorschlag:**
```rust
// In run_engine_loop (Zeile 414-419):
// VORHER:
if let Some(reply) = try_fast_path_dns_reply(&packet, &stats) {
    let _ = device.writer.write_all(&reply);
} else {
    ensure_listener_for_packet(&packet, &mut sockets, &mut tcp_listeners, &mut udp_listeners);
    device.inbound.push_back(packet);
}

// NACHHER:
if let Some(reply) = try_fast_path_dns_reply(&packet, &stats) {
    let _ = device.writer.write_all(&reply);
} else {
    ensure_listener_for_packet(&packet, &mut sockets, &mut tcp_listeners, &mut udp_listeners);
}
device.inbound.push_back(packet);  // ← IMMER hinzufügen!
```

---

#### Hypothese 3: smoltcp-Polling-Problem ⭐⭐⭐
**Datei:** `engine.rs` (Zeile 426)  
**Beschreibung:**
Der Engine-Loop ruft `iface.poll(now, &mut device, &mut sockets)` auf, **ABER** nur wenn ein Paket empfangen wurde oder ein Timeout auftrat. Wenn der Kanal leer ist, wird `poll` **nicht** aufgerufen.

**Code:**
```rust
while stop.load(Ordering::SeqCst) {
    match rx.recv_timeout(ENGINE_TICK) {
        Ok(packet) => {
            // ... Fast-Path DNS
            ensure_listener_for_packet(...);
            device.inbound.push_back(packet);
        }
        Err(RecvTimeoutError::Timeout) => {}
        Err(RecvTimeoutError::Disconnected) => break,
    }

    let now = SmolInstant::from_millis(Instant::now().elapsed().as_millis() as i64);
    iface.poll(now, &mut device, &mut sockets);  // ← Nur wenn Paket oder Timeout
    // ... Rest
}
```

**Problem:**
- Wenn **kein** Paket ankommt, wird `poll` nur alle `ENGINE_TICK` (50ms) aufgerufen
- Aber: `ensure_listener_for_packet` wird **NUR** aufgerufen, wenn ein Paket ankommt
- Wenn der erste TCP-SYN **vor** dem ersten `poll`-Aufruf ankommt, könnte der Listener nicht rechtzeitig bereit sein

**Fix-Vorschlag:**
```rust
// IMMER poll aufrufen, unabhängig von Paketen
let now = SmolInstant::from_millis(Instant::now().elapsed().as_millis() as i64);
iface.poll(now, &mut device, &mut sockets);

// Dann Pakete verarbeiten
match rx.recv_timeout(ENGINE_TICK) {
    Ok(packet) => {
        if let Some(reply) = try_fast_path_dns_reply(&packet, &stats) {
            let _ = device.writer.write_all(&reply);
        } else {
            ensure_listener_for_packet(&packet, &mut sockets, &mut tcp_listeners, &mut udp_listeners);
        }
        device.inbound.push_back(packet);
    }
    Err(RecvTimeoutError::Timeout) => {}
    Err(RecvTimeoutError::Disconnected) => break,
}
```

---

#### Hypothese 4: Blocklist nicht korrekt gesetzt ⭐⭐⭐
**Datei:** `WardenVpnService.kt` (Zeile 142-143)  
**Beschreibung:**
Die Blocklist wird **vor** dem Start des Tunnels gesetzt. Wenn `BarbicanEngine.setBlocklist()` nach dem Start aufgerufen wird, könnte die Rust-Engine die neue Blocklist nicht sehen.

**Code:**
```kotlin
val blocklistStore = DomainBlocklistStore(DomainBlocklistStore.buildEnvelopeFile(this))
BarbicanEngine.setBlocklist(blocklistStore.effectiveBlocklist())
Log.i(TAG, "Calling startCapturedTunnel(fd=${pfd.fd}, ipv4=$TUNNEL_IPV4)")
BarbicanEngine.startCapturedTunnel(pfd.fd, TUNNEL_IPV4, TUNNEL_DNS_IPV4, UPSTREAM_DNS_IPV4, this)
```

**Problem:**
- `setBlocklist` setzt nur eine globale Variable (`BLOCKLIST` in `engine.rs`)
- Wenn der Engine-Loop bereits läuft, sieht er die neue Blocklist möglicherweise nicht
- Die Rust-Engine liest die Blocklist mit `current_blocklist()` (Zeile 172-178), die die RwLock liest

**Fix-Vorschlag:**
```rust
// In engine.rs, set_blocklist:
#[uniffi::export]
pub fn set_blocklist(domains: Vec<String>) {
    let normalized = domains
        .into_iter()
        .map(|d| d.trim_end_matches('.').to_ascii_lowercase())
        .collect();
    if let Ok(mut guard) = BLOCKLIST.write() {
        *guard = Some(normalized);
        // Hier: Aktive Engine benachrichtigen?
    }
}
```

---

## 🎯 **Priorisierte Hypothesen (für Testing)**

| # | Hypothese | Wahrscheinlichkeit | Aufwand zu Testen | Aufwand zu Fixen |
|---|-----------|---------------------|-------------------|------------------|
| 1 | **smoltcp-Routing-Problem** (Default-Route = Tunnel-Adresse) | ⭐⭐⭐⭐ | Niedrig | Niedrig |
| 2 | **Paket nicht in Warteschlange** (nur DNS-Pakete werden gepusht) | ⭐⭐⭐⭐⭐ | Niedrig | Niedrig |
| 3 | **Polling-Reihenfolge** (`poll` nur bei Paket/Timeout) | ⭐⭐⭐ | Niedrig | Niedrig |
| 4 | **Blocklist-Synchronisation** | ⭐⭐ | Mittel | Mittel |

---

## 🔬 **Empfohlene Debug-Schritte**

### Schritt 1: Logging erweitern (Kotlin-Seite)
In `WardenVpnService.kt`:
```kotlin
// In startTunnel():
Log.i(TAG, "TUN established, fd=${pfd.fd}")
Log.i(TAG, "Blocklist size: ${blocklistStore.effectiveBlocklist().size}")
Log.i(TAG, "Calling startCapturedTunnel...")
BarbicanEngine.startCapturedTunnel(...)
Log.i(TAG, "startCapturedTunnel returned, running=${BarbicanEngine.isCapturedTunnelRunning()}")

// In openTcp/openUdp:
Log.i(TAG, "openTcp($dstIp:$dstPort) called")
```

### Schritt 2: Logging erweitern (Rust-Seite)
In `engine.rs`:
```rust
// In run_engine_loop:
while stop.load(Ordering::SeqCst) {
    match rx.recv_timeout(ENGINE_TICK) {
        Ok(packet) => {
            log::info!("Received packet of {} bytes", packet.len());
            // ...
        }
        Err(RecvTimeoutError::Timeout) => {
            log::trace!("Engine tick (no packet)");
        }
        Err(RecvTimeoutError::Disconnected) => {
            log::error!("Channel disconnected");
            break;
        }
    }
    
    log::trace!("Calling iface.poll()");
    iface.poll(now, &mut device, &mut sockets);
    log::trace!("iface.poll() returned");
    // ...
}
```

### Schritt 3: Test mit einfachen DNS-Anfragen
1. Tunnel starten
2. `dig @10.64.0.2 example.com` (DNS-Sentinel-Adresse)
3. Prüfen, ob Pakete im Rust-Log auftauchen
4. Prüfen, ob `try_fast_path_dns_reply` aufgerufen wird

### Schritt 4: Test mit TCP-Verbindung
1. Tunnel starten
2. `curl --interface tun0 http://example.com`
3. Prüfen, ob TCP-SYN im Rust-Log auftaucht
4. Prüfen, ob `ensure_listener_for_packet` aufgerufen wird

---

## 📋 **Zusammenfassung der nächsten Schritte**

### Phase 0, Tag 1-2: Hypothesen-Testing
1. **Hypothese 2 testen** (Paket nicht in Warteschlange)
   - Fix implementieren: IMMER `device.inbound.push_back(packet)` aufrufen
   - Testen mit DNS-Anfrage
   - **Erwartung:** DNS-Pakete werden verarbeitet

2. Falls nicht behoben: **Hypothese 1 testen** (smoltcp-Routing)
   - Fix: Default-Route auf `0.0.0.0` statt `smol_addr` setzen
   - Testen mit DNS-Anfrage

3. Falls nicht behoben: **Hypothese 3 testen** (Polling-Reihenfolge)
   - Fix: `iface.poll()` IMMER aufrufen
   - Testen mit DNS-Anfrage

### Phase 0, Tag 3: Dokumentation
- Ergebnis des Testings dokumentieren
- Finalen Fix identifizieren
- Barbican-Code mit Fixes reaktivieren

---

## 📚 **Referenzierte Dateien**

| Datei | Relevanz |
|-------|----------|
| [app/netlock-disabled/README.md](../app/netlock-disabled/README.md) | Beschreibt den Kernfehler |
| [app/netlock-disabled/de/ble1st/warden/netlock/WardenVpnService.kt](../app/netlock-disabled/de/ble1st/warden/netlock/WardenVpnService.kt) | Haupt-VPN-Service |
| [app/netlock-disabled/de/ble1st/warden/netlock/BarbicanEngine.kt](../app/netlock-disabled/de/ble1st/warden/netlock/BarbicanEngine.kt) | Facade für Rust-Engine |
| [rust/barbican/src/engine.rs](../rust/barbican/src/engine.rs) | Rust-Engine (Haupt-Loop) |
| [rust/barbican/src/dns_filter.rs](../rust/barbican/src/dns_filter.rs) | DNS-Filter-Logik |
| [rust/barbican/src/nat.rs](../rust/barbican/src/nat.rs) | NAT-Tabelle |

---

## 🔄 **Version History**

| Version | Datum | Änderungen | Autor |
|---------|-------|-----------|-------|
| 1.0 | 2026-08-29 | Erstellung der Analyse | Mistral Vibe |
