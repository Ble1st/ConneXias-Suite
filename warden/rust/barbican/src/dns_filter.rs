//! "Netz-Sperre" (2026-08-27): Blocklisten-Prüfung für DNS-Anfragen, die durch den Tunnel laufen
//! (s. `engine.rs`s Paket-Lese-Loop). Arbeitet ausschließlich auf dem UDP-Nutzlast-Anteil eines
//! DNS-Pakets (Framing/IP-Rekonstruktion passiert außerhalb, in `engine.rs`/`nat.rs`) — bewusst
//! ein reines, framework-freies Modul, dieselbe "Entscheidungslogik von der I/O-Schicht trennen"-
//! Haltung wie Wardens Kotlin-seitiges `domain/*` (s. CLAUDE.md).
//!
//! Nur die Question-Section wird geparst (QNAME) — genug für den Blocklisten-Zweck. Kein
//! vollständiger DNS-Parser (Antwort-Records, Kompression in Antworten etc. werden nicht
//! gebraucht, da nur echte Client-Queries hier ankommen).

use std::collections::HashSet;

/// Ergebnis des DNS-Query-Parsens.
pub enum ParseResult {
    /// Gültiger Domainname — gegen die Blockliste prüfen.
    Name(String),
    /// Strukturell ungültiges Paket — Aufrufer behandelt als "nicht blockiert" (fail-open für
    /// die Parse-Ebene; die Sicherheitsentscheidung ist ohnehin nur "diese Domain sperren").
    Invalid,
    /// Nicht-UTF8-konforme Labels — Fail-closed: blockieren, um Bypass über kodierte Labels
    /// zu verhindern.
    Blocked,
}

impl std::fmt::Debug for ParseResult {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ParseResult::Name(n) => write!(f, "Name({n})"),
            ParseResult::Invalid => write!(f, "Invalid"),
            ParseResult::Blocked => write!(f, "Blocked"),
        }
    }
}

/// Liest den QNAME aus der Question-Section eines DNS-Query-Pakets (roher UDP-Payload, Port 53).
/// `Invalid` bei jedem strukturell ungültigen/zu kurzen Paket. `Blocked` bei nicht-UTF8-Labels.
///
/// DNS-Namenskompression (Zeiger, oberste zwei Bits `11`) wird in der *Question*-Section laut
/// RFC 1035 nicht verwendet (es gibt noch keine vorherigen Namen, auf die verwiesen werden
/// könnte) — ein Kompressions-Zeiger hier gilt deshalb als ungültig, nicht als zu behandelnder
/// Sonderfall.
pub fn parse_query_name(udp_payload: &[u8]) -> ParseResult {
    const HEADER_LEN: usize = 12;
    if udp_payload.len() < HEADER_LEN + 1 {
        return ParseResult::Invalid;
    }
    let qdcount = u16::from_be_bytes([udp_payload[4], udp_payload[5]]);
    if qdcount == 0 {
        return ParseResult::Invalid;
    }

    let mut pos = HEADER_LEN;
    let mut labels: Vec<&str> = Vec::new();
    loop {
        let len = match udp_payload.get(pos) {
            Some(&b) => b as usize,
            None => return ParseResult::Invalid,
        };
        if len == 0 {
            pos += 1;
            break;
        }
        if len >= 0xC0 {
            // Kompressions-Zeiger in der Question-Section — laut RFC 1035 hier nie gültig, s.
            // Doc oben.
            return ParseResult::Invalid;
        }
        pos += 1;
        let label_bytes = match udp_payload.get(pos..pos + len) {
            Some(bytes) => bytes,
            None => return ParseResult::Invalid,
        };
        // Fail-closed: nicht-UTF8-konforme Labels werden als blockiert behandelt, nicht als
        // "ungültig" verworfen. Ein Angreifer könnte sonst die Blockliste über absichtlich
        // nicht-UTF8-konforme Labels umgehen.
        let label = match std::str::from_utf8(label_bytes) {
            Ok(s) => s,
            Err(_) => return ParseResult::Blocked,
        };
        labels.push(label);
        pos += len;
    }
    if labels.is_empty() {
        return ParseResult::Invalid;
    }
    // QTYPE/QCLASS (4 Bytes) müssen noch folgen, sonst ist die Question-Section unvollständig.
    if udp_payload.get(pos..pos + 4).is_none() {
        return ParseResult::Invalid;
    }
    ParseResult::Name(labels.join("."))
}

/// Suffix-Match: `sub.ads.example.com` ist blockiert, wenn `ads.example.com` (oder `example.com`,
/// `com`, ...) in der Blockliste steht — der übliche "Domain + alle Subdomains" Erwartungswert
/// für eine Blockliste, kein reiner Exact-Match. Vergleich case-insensitiv (DNS-Namen sind es
/// laut RFC 1035 auch).
pub fn is_blocked(name: &str, blocklist: &HashSet<String>) -> bool {
    let name = name.trim_end_matches('.').to_ascii_lowercase();
    if blocklist.contains(&name) {
        return true;
    }
    let mut rest = name.as_str();
    while let Some((_, tail)) = rest.split_once('.') {
        if blocklist.contains(tail) {
            return true;
        }
        rest = tail;
    }
    false
}

/// Baut eine minimale, RFC-1035-konforme NXDOMAIN-Antwort für [query] (roher UDP-Payload der
/// Anfrage) — echot Transaktions-ID und Question-Section unverändert (üblich/erwartet), setzt
/// QR=1 (Antwort), RA=1 (rekursiv verfügbar — Warden beantwortet direkt, ohne echten Upstream-
/// Roundtrip), RCODE=3 (NXDOMAIN), AN/NS/AR-Count=0 (keine Records).
pub fn build_nxdomain_reply(query: &[u8]) -> Option<Vec<u8>> {
    if query.len() < 12 {
        return None;
    }
    let mut reply = Vec::with_capacity(query.len());
    reply.extend_from_slice(&query[0..2]); // ID, unverändert
    let request_flags = u16::from_be_bytes([query[2], query[3]]);
    let rd = request_flags & 0x0100; // Recursion-Desired-Bit vom Client übernehmen
    let response_flags: u16 = 0x8000 // QR=1 (response)
        | rd
        | 0x0080 // RA=1 (recursion available)
        | 0x0003; // RCODE=3 (NXDOMAIN)
    reply.extend_from_slice(&response_flags.to_be_bytes());
    reply.extend_from_slice(&1u16.to_be_bytes()); // QDCOUNT=1 (Question echoed)
    reply.extend_from_slice(&0u16.to_be_bytes()); // ANCOUNT
    reply.extend_from_slice(&0u16.to_be_bytes()); // NSCOUNT
    reply.extend_from_slice(&0u16.to_be_bytes()); // ARCOUNT
    reply.extend_from_slice(&query[12..]); // Question-Section unverändert übernehmen
    Some(reply)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Baut ein minimales, gültiges DNS-Query-Paket für `name` (Standard-Query, Typ A, Klasse IN)
    /// — Test-Fixture, kein Produktionscode.
    fn build_query(name: &str) -> Vec<u8> {
        let mut packet = vec![0x12, 0x34]; // ID
        packet.extend_from_slice(&0x0100u16.to_be_bytes()); // Flags: RD=1
        packet.extend_from_slice(&1u16.to_be_bytes()); // QDCOUNT
        packet.extend_from_slice(&0u16.to_be_bytes()); // ANCOUNT
        packet.extend_from_slice(&0u16.to_be_bytes()); // NSCOUNT
        packet.extend_from_slice(&0u16.to_be_bytes()); // ARCOUNT
        for label in name.split('.') {
            packet.push(label.len() as u8);
            packet.extend_from_slice(label.as_bytes());
        }
        packet.push(0); // root label
        packet.extend_from_slice(&1u16.to_be_bytes()); // QTYPE=A
        packet.extend_from_slice(&1u16.to_be_bytes()); // QCLASS=IN
        packet
    }

    #[test]
    fn parses_simple_query_name() {
        let query = build_query("ads.example.com");
        match parse_query_name(&query) {
            ParseResult::Name(n) => assert_eq!(n, "ads.example.com"),
            other => panic!("expected Name, got {:?}", other),
        }
    }

    #[test]
    fn rejects_truncated_packet() {
        assert!(matches!(parse_query_name(&[0u8; 5]), ParseResult::Invalid));
    }

    #[test]
    fn rejects_zero_qdcount() {
        let mut query = build_query("example.com");
        query[4] = 0;
        query[5] = 0;
        assert!(matches!(parse_query_name(&query), ParseResult::Invalid));
    }

    #[test]
    fn rejects_compression_pointer_in_question() {
        let mut query = build_query("example.com");
        // Ersten Label-Length-Byte durch einen Kompressions-Zeiger (>= 0xC0) ersetzen.
        query[12] = 0xC0;
        assert!(matches!(parse_query_name(&query), ParseResult::Invalid));
    }

    #[test]
    fn exact_match_is_blocked() {
        let blocklist: HashSet<String> = ["ads.example.com".to_string()].into_iter().collect();
        assert!(is_blocked("ads.example.com", &blocklist));
    }

    #[test]
    fn subdomain_of_blocked_domain_is_blocked() {
        let blocklist: HashSet<String> = ["ads.example.com".to_string()].into_iter().collect();
        assert!(is_blocked("tracker.ads.example.com", &blocklist));
    }

    #[test]
    fn unrelated_domain_is_not_blocked() {
        let blocklist: HashSet<String> = ["ads.example.com".to_string()].into_iter().collect();
        assert!(!is_blocked("example.com", &blocklist));
        assert!(!is_blocked("wikipedia.org", &blocklist));
    }

    #[test]
    fn match_is_case_insensitive() {
        let blocklist: HashSet<String> = ["ads.example.com".to_string()].into_iter().collect();
        assert!(is_blocked("ADS.EXAMPLE.COM", &blocklist));
    }

    #[test]
    fn nxdomain_reply_echoes_id_and_question() {
        let query = build_query("blocked.example.com");
        let reply = build_nxdomain_reply(&query).expect("reply");
        assert_eq!(&reply[0..2], &query[0..2], "ID muss übernommen werden");
        let flags = u16::from_be_bytes([reply[2], reply[3]]);
        assert_eq!(flags & 0x8000, 0x8000, "QR-Bit muss gesetzt sein");
        assert_eq!(flags & 0x000F, 3, "RCODE muss NXDOMAIN (3) sein");
        assert_eq!(
            &reply[12..],
            &query[12..],
            "Question-Section muss unverändert echoen"
        );
    }

    #[test]
    fn nxdomain_reply_rejects_too_short_query() {
        assert_eq!(build_nxdomain_reply(&[0u8; 4]), None);
    }
}
