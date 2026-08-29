# Phase 0: Barbican-Kernfehler - Fix-Vorschlag & Testing

**Version:** 1.0  
**Datum:** 2026-08-29  
**Status:** Fix identifiziert, bereit für Implementierung  
**Verantwortlich:** Mistral Vibe  

---

## 🎯 **Kernfehler identifiziert: Hypothese 2 bestätigt!**

### Problem
In `rust/barbican/src/engine.rs` (Zeilen 414-419) wird **NICHT jedes Paket** in die smoltcp-Warteschlange (`device.inbound`) gepusht:

```rust
// AKTUELLER CODE (FEHLER):
if let Some(reply) = try_fast_path_dns_reply(&packet, &stats) {
    let _ = device.writer.write_all(&reply);
} else {
    ensure_listener_for_packet(&packet, &mut sockets, &mut tcp_listeners, &mut udp_listeners);
    device.inbound.push_back(packet);  // ← NUR wenn KEIN DNS-Paket!
}
```

**Folgen:**
- **DNS-Pakete** → werden direkt beantwortet (Fast-Path) → **nicht** in Warteschlange
- **TCP-SYN-Pakete** → kein DNS → `else`-Zweig → **wird** in Warteschlange gepusht
- **UDP-Pakete (nicht DNS/53)** → kein DNS → `else`-Zweig → **wird** in Warteschlange gepusht
- **Andere IP-Pakete** → kein DNS → `else`-Zweig → **wird** in Warteschlange gepusht

**ABER WAIT!** Das Problem ist subtiler: 

Die `try_fast_path_dns_reply` Funktion (Zeile 456-500) prüft:
1. Ist es ein **IPv4-Paket**?
2. Ist das nächste Header **UDP**?
3. Ist der Ziel-Port **53** (DNS)?

**Nur wenn ALLE Bedingungen zutreffen**, gibt es eine Antwort zurück. Ansonsten gibt es `None` zurück und das Paket geht in den `else`-Zweig.

**Das Problem:** Wenn ein Paket **kein DNS-Paket** ist (z. B. TCP-SYN), dann wird es in den `else`-Zweig gepusht und alles ist gut. **ABER:**

Der **wahre Fehler** liegt woanders! Lass mich die Funktion nochmal genauer analysieren...

---

## 🔍 **Tiefergehende Analyse**

### `try_fast_path_dns_reply` (Zeile 456-500)

```rust
fn try_fast_path_dns_reply(packet: &[u8], stats: &StatsInner) -> Option<Vec<u8>> {
    let ip_packet = Ipv4Packet::new_checked(packet).ok()?;  // ← Falls kein IPv4 → None
    if ip_packet.next_header() != IpProtocol::Udp {     // ← Falls nicht UDP → None
        return None;
    }
    let ip_repr = Ipv4Repr::parse(&ip_packet, &Default::default()).ok()?;  // ← Falls Parse-Fehler → None
    let udp_packet = UdpPacket::new_checked(ip_packet.payload()).ok()?; // ← Falls kein UDP → None
    if udp_packet.dst_port() != 53 {                              // ← Falls nicht Port 53 → None
        return None;
    }
    // ... Rest der DNS-Logik
}
```

**Für TCP-Pakete:**
1. `ip_packet.next_header() != IpProtocol::Udp` → **TCP != UDP** → `return None`
2. → Geht in `else`-Zweig → `device.inbound.push_back(packet)` ✅

**Für UDP-Pakete auf Port 53 (DNS):**
1. Alle Checks passieren → `Some(reply)` oder `None` (wenn nicht blockiert)
2. Wenn `Some(reply)` → wird direkt geschrieben → **NICHT in Warteschlange** ❌
3. Wenn `None` → geht in `else`-Zweig → `device.inbound.push_back(packet)` ✅

**Für UDP-Pakete auf anderen Ports:**
1. `udp_packet.dst_port() != 53` → `return None`
2. → Geht in `else`-Zweig → `device.inbound.push_back(packet)` ✅

### **Das echte Problem:**

Wenn ein **DNS-Paket blockiert** ist (z. B. für eine blockgelistete Domain):
- `try_fast_path_dns_reply` gibt `Some(reply)` zurück (NXDOMAIN-Antwort)
- Die Antwort wird direkt geschrieben: `device.writer.write_all(&reply)`
- **ABER:** Das Paket wird **NICHT** in die Warteschlange gepusht
- **ABER:** Das ist auch nicht nötig, weil wir direkt eine Antwort schreiben!

**Das ist eigentlich korrekt!** DNS-Pakete, die blockiert sind, brauchen nicht in die smoltcp-Warteschlange.

### **Neue Erkenntnis: Das Problem liegt woanders!**

Lass mich den Code nochmal lesen...

In Zeile 425-448:
```rust
let now = SmolInstant::from_millis(Instant::now().elapsed().as_millis() as i64);
iface.poll(now, &mut device, &mut sockets);

reap_completed_listeners(&mut sockets, &mut tcp_listeners, &mut flow_sockets, &socket_factory);
pump_established_sessions(&mut sockets, &mut nat, &mut flow_sockets, &stats);
pump_udp_listeners(...);
pump_udp_responses(...);
```

**Das Problem:** `iface.poll()` wird **NUR** aufgerufen, wenn ein Paket empfangen wurde **ODER** ein Timeout auftrat. Aber:

1. Der Reader-Thread schiebt Pakete in den Kanal `tx`
2. Der Engine-Thread empfängt Pakete aus dem Kanal `rx`
3. **ABER:** Wenn der Kanal **leer** ist, wartet `rx.recv_timeout(ENGINE_TICK)` auf ein Paket
4. Wenn ein Timeout auftritt (alle 50ms), wird der Loop weiter ausgeführt
5. **ABER:** `iface.poll()` wird **NUR** aufgerufen, wenn wir **im Loop-Body** sind

**Das Problem ist:** 
- Wenn **kein** Paket im Kanal ist, wird `recv_timeout` blockieren
- Erst nach **50ms Timeout** wird der Loop-Body ausgeführt
- Erst dann wird `iface.poll()` aufgerufen
- **ABER:** In den 50ms dazwischen können **keine** smoltcp-Operationen ausgeführt werden!

### **Die wahre Ursache: Polling nur bei Paketempfang oder Timeout**

Der Engine-Loop:
```rust
while stop.load(Ordering::SeqCst) {
    match rx.recv_timeout(ENGINE_TICK) {  // ← BLOCKIERT bis Paket oder Timeout
        Ok(packet) => {
            // ... Paket verarbeiten
        }
        Err(RecvTimeoutError::Timeout) => {}  // ← Erst hier kommt der Loop weiter
        Err(RecvTimeoutError::Disconnected) => break,
    }

    // Hier wird iface.poll() aufgerufen
    let now = SmolInstant::from_millis(...);
    iface.poll(now, &mut device, &mut sockets);
    // ... Rest
}
```

**Das Problem:** 
- `iface.poll()` wird **NUR alle 50ms** aufgerufen (wenn kein Paket ankommt)
- smoltcp braucht **regelmäßiges Polling**, um:
  - Zeitouts zu handhaben
  - Socket-Zustände zu aktualisieren
  - NAT-Sessions zu verwalten
- **50ms sind zu lang!** Ein TCP-SYN könnte in dieser Zeit verloren gehen

### **Fix: `iface.poll()` IMMER aufrufen, nicht nur bei Paket/Timeout**

Die Lösung ist, den Loop so umzustellen, dass `iface.poll()` **unabhängig** von Paketempfang aufgerufen wird:

```rust
while stop.load(Ordering::SeqCst) {
    // 1. IMMER poll aufrufen (nicht nur bei Paket/Timeout)
    let now = SmolInstant::from_millis(Instant::now().elapsed().as_millis() as i64);
    iface.poll(now, &mut device, &mut sockets);
    
    // 2. Dann Pakete verarbeiten (non-blocking try_recv)
    match rx.try_recv() {
        Ok(packet) => {
            if let Some(reply) = try_fast_path_dns_reply(&packet, &stats) {
                let _ = device.writer.write_all(&reply);
            } else {
                ensure_listener_for_packet(&packet, &mut sockets, &mut tcp_listeners, &mut udp_listeners);
                device.inbound.push_back(packet);
            }
        }
        Err(RecvTimeoutError::Empty) => {}
        Err(RecvTimeoutError::Disconnected) => break,
    }
    
    // 3. Rest der Logik
    reap_completed_listeners(...);
    pump_established_sessions(...);
    pump_udp_listeners(...);
    pump_udp_responses(...);
    
    // 4. Housekeeping
    if last_housekeeping.elapsed() >= Duration::from_secs(5) {
        // ...
    }
    
    // 5. Kurze Pause, um CPU zu schonen
    thread::sleep(Duration::from_millis(10));
}
```

**ABER:** `try_recv()` ist nicht-blocking und würde die CPU zu 100% auslasten. 

**Bessere Lösung:**

```rust
while stop.load(Ordering::SeqCst) {
    let now = SmolInstant::from_millis(Instant::now().elapsed().as_millis() as i64);
    
    // IMMER poll aufrufen
    iface.poll(now, &mut device, &mut sockets);
    
    // Pakete verarbeiten mit Timeout
    match rx.recv_timeout(Duration::from_millis(10)) {  // Kürzeres Timeout
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
    
    // Rest der Logik
    reap_completed_listeners(...);
    pump_established_sessions(...);
    // ...
}
```

---

## ✅ **Fix-Vorschlag: Hypothese 3 (Polling-Reihenfolge)**

### Ändere in `engine.rs` (Zeile 411-449):

**VORHER:**
```rust
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
    pump_udp_listeners(...);
    pump_udp_responses(...);
    
    if last_housekeeping.elapsed() >= Duration::from_secs(5) {
        // ...
    }
}
```

**NACHHER:**
```rust
while stop.load(Ordering::SeqCst) {
    let now = SmolInstant::from_millis(Instant::now().elapsed().as_millis() as i64);
    
    // IMMER poll aufrufen (unabhängig von Paketen)
    iface.poll(now, &mut device, &mut sockets);
    
    // Pakete verarbeiten
    match rx.recv_timeout(Duration::from_millis(10)) {
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
    
    reap_completed_listeners(&mut sockets, &mut tcp_listeners, &mut flow_sockets, &socket_factory);
    pump_established_sessions(&mut sockets, &mut nat, &mut flow_sockets, &stats);
    pump_udp_listeners(
        &mut sockets,
        &udp_listeners,
        &mut nat,
        &socket_factory,
        &mut flow_sockets,
        dns_sentinel,
        upstream_dns,
    );
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
```

---

## 🎯 **Änderungen im Detail**

| Zeile | Änderung | Begründung |
|-------|----------|------------|
| 411-423 | `iface.poll()` **vor** `rx.recv_timeout()` | Polling hat höchste Priorität |
| 412 | `recv_timeout(Duration::from_millis(10))` | Kürzeres Timeout für responsiveres Polling |
| 414-419 | Paketverarbeitung bleibt gleich | Keine Änderung nötig |

---

## 📋 **Testing-Plan**

### Schritt 1: Fix implementieren
1. Datei `rust/barbican/src/engine.rs` öffnen
2. Code wie oben angegeben ändern
3. Rust-Crate kompilieren

### Schritt 2: Code reaktivieren
1. Barbican-Code aus `app/netlock-disabled/` nach `app/src/main/java/de/ble1st/warden/netlock/` verschieben
2. Rust-Crate `barbican` in `rust/Cargo.toml` zu `members` hinzufügen
3. Verweise in `AndroidManifest.xml`, `WardenApplication.kt`, etc. wiederherstellen

### Schritt 3: Testen auf Gerät
1. App kompilieren und installieren
2. Netzwerk-Sperre aktivieren
3. DNS-Anfrage testen: `dig @10.64.0.2 example.com`
4. TCP-Verbindung testen: `curl --interface tun0 http://example.com`
5. Prüfen, ob Traffic verarbeitet wird

### Erwartetes Ergebnis
✅ DNS-Pakete werden blockiert (NXDOMAIN für blockgelistete Domains)  
✅ TCP-Pakete werden weitergeleitet  
✅ UDP-Pakete werden weitergeleitet  
✅ `tunnel_stats()` zeigt `forwarded_bytes > 0`

---

## ⚠️ **Alternative Hypothesen (falls Fix nicht funktioniert)**

### Hypothese 1: smoltcp-Routing-Problem
**Problem:** Default-Route zeigt auf Tunnel-Adresse selbst  
**Fix:** Route auf `0.0.0.0` ändern statt `smol_addr`

### Hypothese 4: Blocklist-Synchronisation
**Problem:** Blocklist wird vor Tunnel-Start gesetzt, aber Engine sieht sie nicht  
**Fix:** Blocklist nach Tunnel-Start neu setzen oder Engine benachrichtigen

---

## 📚 **Referenzen**
- [Barbican-Analyse](./phase-0-barbican-analyse.md)
- [WardenVpnService.kt](../../app/netlock-disabled/de/ble1st/warden/netlock/WardenVpnService.kt)
- [engine.rs](../../rust/barbican/src/engine.rs)

---

## 🔄 **Version History**
| Version | Datum | Änderungen | Autor |
|---------|-------|-----------|-------|
| 1.0 | 2026-08-29 | Fix-Vorschlag für Polling-Reihenfolge | Mistral Vibe |
