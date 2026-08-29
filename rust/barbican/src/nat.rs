//! "Netz-Sperre" (2026-08-27): reine Verbindungstabelle für den NAT-Teil — bewusst **ohne** jede
//! smoltcp-/Socket-/I-O-Abhängigkeit, damit sie wie Wardens Kotlin-seitiges `domain/*` ohne echtes
//! Netzwerk/TUN unit-testbar bleibt (s. CLAUDE.md "Decision/Executor separation"-Konvention,
//! hier auf die Rust-Seite übertragen). Die eigentliche smoltcp-/Socket-Verdrahtung (welcher
//! `SocketHandle`/fd zu welcher Session gehört, das Byte-Relaying selbst) lebt in `engine.rs` und
//! nutzt diese Tabelle nur als Buchführung: wer ist gerade offen, wer ist seit wann untätig, wann
//! muss verdrängt werden.

use std::collections::HashMap;
use std::time::Instant;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Proto {
    Tcp,
    Udp,
}

/// Fünf-Tupel, das eine NAT-Session eindeutig identifiziert — `src_*` ist die anfragende App
/// (Tunnel-seitig), `dst_*` das reale Ziel.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct FlowKey {
    pub proto: Proto,
    pub src_ip: String,
    pub src_port: u16,
    pub dst_ip: String,
    pub dst_port: u16,
}

/// Buchführung zu einer offenen Session — `external_fd` ist der von
/// [crate::callback::ProtectedSocketFactory] beschaffte reale Socket, `smoltcp_handle` ein
/// opakes Handle (bei UDP: Index in den geteilten Port-Listenern statt eines eigenen Handles pro
/// Session — s. `engine.rs`, hier bewusst nur als `u64` durchgereicht, keine smoltcp-Typen in
/// diesem Modul).
#[derive(Debug, Clone)]
pub struct NatSession {
    pub external_fd: i32,
    pub smoltcp_handle: u64,
    pub last_active: Instant,
}

/// Begrenzte, LRU-artige Verbindungstabelle — verhindert unbegrenztes fd-/Speicherwachstum in
/// einem Dauer-Foreground-Service (s. Plan Abschnitt 1: `MAX_SESSIONS`, Idle-Timeouts). Kein
/// echtes LRU mit eigener verketteter Liste — bei [MAX_SESSIONS] im vierstelligen Bereich genügt
/// ein linearer Scan nach dem ältesten Eintrag (`last_active`) bei jeder Verdrängung; deutlich
/// weniger Codekomplexität als eine intrusive LRU-Liste, und der Scan läuft nur beim seltenen
/// "Tabelle voll"-Fall, nicht auf dem heißen Paket-Pfad.
pub struct NatTable {
    sessions: HashMap<FlowKey, NatSession>,
    max_sessions: usize,
}

impl NatTable {
    pub fn new(max_sessions: usize) -> Self {
        Self {
            sessions: HashMap::new(),
            max_sessions,
        }
    }

    pub fn get(&self, key: &FlowKey) -> Option<&NatSession> {
        self.sessions.get(key)
    }

    pub fn touch(&mut self, key: &FlowKey, now: Instant) {
        if let Some(session) = self.sessions.get_mut(key) {
            session.last_active = now;
        }
    }

    pub fn len(&self) -> usize {
        self.sessions.len()
    }

    pub fn is_empty(&self) -> bool {
        self.sessions.is_empty()
    }

    /// Iteriert alle aktuell offenen Sessions — genutzt von `engine.rs`, um z. B. für jede
    /// UDP-Session nicht-blockierend nach eingetroffenen Antworten auf dem externen Socket zu
    /// schauen (Extern→App-Richtung, s. dortiges `pump_udp_responses`).
    pub fn iter(&self) -> impl Iterator<Item = (&FlowKey, &NatSession)> {
        self.sessions.iter()
    }

    /// Legt eine neue Session an. Ist die Tabelle bereits voll, wird zuerst die am längsten
    /// untätige Session verdrängt und als [Evicted] zurückgegeben — Aufrufer (`engine.rs`) muss
    /// deren `external_fd`/`smoltcp_handle` real schließen, diese Tabelle tut das nicht selbst
    /// (kein I/O in diesem Modul, s. Klassendoc).
    pub fn insert(&mut self, key: FlowKey, session: NatSession) -> Option<(FlowKey, NatSession)> {
        let evicted =
            if self.sessions.len() >= self.max_sessions && !self.sessions.contains_key(&key) {
                self.oldest_key().map(|k| {
                    let s = self.sessions.remove(&k).expect("oldest_key must exist");
                    (k, s)
                })
            } else {
                None
            };
        self.sessions.insert(key, session);
        evicted
    }

    pub fn remove(&mut self, key: &FlowKey) -> Option<NatSession> {
        self.sessions.remove(key)
    }

    /// Räumt alle Sessions, deren `last_active` älter als [timeout] ist, und gibt sie zum realen
    /// Schließen zurück (dieselbe "kein I/O hier"-Begründung wie [insert]).
    pub fn evict_idle(
        &mut self,
        now: Instant,
        tcp_timeout: std::time::Duration,
        udp_timeout: std::time::Duration,
    ) -> Vec<(FlowKey, NatSession)> {
        let mut result = Vec::new();
        let keys: Vec<FlowKey> = self
            .sessions
            .iter()
            .filter(|(key, session)| {
                let timeout = match key.proto {
                    Proto::Tcp => tcp_timeout,
                    Proto::Udp => udp_timeout,
                };
                now.duration_since(session.last_active) >= timeout
            })
            .map(|(key, _)| key.clone())
            .collect();
        for key in keys {
            if let Some(session) = self.sessions.remove(&key) {
                result.push((key, session));
            }
        }
        result
    }

    fn oldest_key(&self) -> Option<FlowKey> {
        self.sessions
            .iter()
            .min_by_key(|(_, session)| session.last_active)
            .map(|(key, _)| key.clone())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    fn key(port: u16) -> FlowKey {
        FlowKey {
            proto: Proto::Tcp,
            src_ip: "10.64.0.2".into(),
            src_port: 40000 + port,
            dst_ip: "93.184.216.34".into(),
            dst_port: 443,
        }
    }

    fn session(now: Instant) -> NatSession {
        NatSession {
            external_fd: 42,
            smoltcp_handle: 0,
            last_active: now,
        }
    }

    #[test]
    fn insert_and_get_round_trips() {
        let mut table = NatTable::new(10);
        let now = Instant::now();
        table.insert(key(1), session(now));
        assert!(table.get(&key(1)).is_some());
        assert_eq!(table.len(), 1);
    }

    #[test]
    fn insert_beyond_capacity_evicts_oldest() {
        let mut table = NatTable::new(2);
        let t0 = Instant::now();
        table.insert(key(1), session(t0));
        table.insert(key(2), session(t0 + Duration::from_secs(1)));
        assert_eq!(table.len(), 2);

        let evicted = table.insert(key(3), session(t0 + Duration::from_secs(2)));
        assert_eq!(table.len(), 2, "Tabelle bleibt auf max_sessions begrenzt");
        let (evicted_key, _) = evicted.expect("eine Session muss verdrängt worden sein");
        assert_eq!(
            evicted_key,
            key(1),
            "die am längsten untätige Session muss verdrängt werden"
        );
        assert!(table.get(&key(1)).is_none());
        assert!(table.get(&key(3)).is_some());
    }

    #[test]
    fn touch_updates_last_active_and_protects_from_eviction() {
        let mut table = NatTable::new(2);
        let t0 = Instant::now();
        table.insert(key(1), session(t0));
        table.insert(key(2), session(t0 + Duration::from_secs(1)));
        table.touch(&key(1), t0 + Duration::from_secs(5));

        let evicted = table.insert(key(3), session(t0 + Duration::from_secs(6)));
        let (evicted_key, _) = evicted.expect("eine Session muss verdrängt worden sein");
        assert_eq!(
            evicted_key,
            key(2),
            "key(1) wurde aktualisiert, key(2) ist jetzt älter"
        );
    }

    #[test]
    fn evict_idle_removes_only_expired_sessions() {
        let mut table = NatTable::new(10);
        let t0 = Instant::now();
        table.insert(key(1), session(t0));
        table.insert(key(2), session(t0 + Duration::from_secs(30)));

        let evicted = table.evict_idle(
            t0 + Duration::from_secs(61),
            Duration::from_secs(60),
            Duration::from_secs(120),
        );
        assert_eq!(evicted.len(), 1);
        assert_eq!(evicted[0].0, key(1));
        assert_eq!(table.len(), 1);
        assert!(table.get(&key(2)).is_some());
    }

    #[test]
    fn evict_idle_respects_different_timeouts_per_proto() {
        let mut table = NatTable::new(10);
        let t0 = Instant::now();
        let mut udp_key = key(1);
        udp_key.proto = Proto::Udp;
        table.insert(udp_key.clone(), session(t0));
        table.insert(key(2), session(t0));

        // Nach 90s: TCP-Timeout (60s) überschritten, UDP-Timeout (120s) noch nicht.
        let evicted = table.evict_idle(
            t0 + Duration::from_secs(90),
            Duration::from_secs(60),
            Duration::from_secs(120),
        );
        assert_eq!(evicted.len(), 1);
        assert_eq!(evicted[0].0, key(2));
        assert!(table.get(&udp_key).is_some());
    }

    #[test]
    fn remove_deletes_session() {
        let mut table = NatTable::new(10);
        table.insert(key(1), session(Instant::now()));
        assert!(table.remove(&key(1)).is_some());
        assert!(table.get(&key(1)).is_none());
    }
}
