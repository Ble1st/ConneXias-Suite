//! Argon2id — Sekundär-PIN-/Passwort-Hashing (Konzept Abschnitt 3: "Sekundär-PIN: ... Argon2id
//! über die Rust-Engine; Verifikationsdaten im Envelope-Schema").
//!
//! Nutzt die RustCrypto-`password-hash`-Konvention (PHC-String-Format), damit Parameter
//! (Speicher-/Zeit-Kosten, Salt) selbstbeschreibend im gespeicherten Hash stecken — kein
//! separates Parameter-Tracking nötig.

use argon2::password_hash::phc::PasswordHash as Argon2PasswordHash;
use argon2::password_hash::{PasswordHasher, PasswordVerifier};
use argon2::{Algorithm, Argon2, Params, Version};
// `PasswordHash` ist an der Modulwurzel seit argon2/password-hash 0.6 nur noch als deprecated
// Re-Export vorhanden ("PHC types moved to the phc crate") — `-D warnings` in der CI würde die
// Deprecation-Warnung sonst als Fehler werten, deshalb der volle `phc`-Pfad.
use std::fmt;

/// Argon2id-Hash im PHC-String-Format (`$argon2id$v=19$m=...,t=...,p=...$salt$hash`) —
/// enthält alle Parameter zur späteren Verifikation, nichts davon geht separat verloren.
///
/// Benanntes Feld statt Tuple-Struct — UniFFI-Records (Meilenstein B.2) brauchen benannte Felder.
/// Kein eigenes `Drop`/Zeroize (anders als in B.1, Begründung wie bei [crate::Envelope]): der
/// PHC-String ist Salt+Hash, kein Geheimnis, und UniFFIs Record-Derive verträgt sich ohnehin
/// nicht mit einem Drop-Impl (verschiebt Felder beim FFI-Übergang).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PasswordHash {
    pub phc: String,
}

impl PasswordHash {
    pub fn as_str(&self) -> &str {
        &self.phc
    }
}

impl fmt::Display for PasswordHash {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.phc)
    }
}

#[derive(Debug, uniffi::Error)]
pub enum PasswordError {
    Hashing,
    /// Der gespeicherte Hash-String ist kein gültiges PHC-Format (z. B. Datenkorruption).
    /// Konzept Invariante 6 (Fail-Safe): als "nicht verifizierbar", nie als "richtig", behandeln.
    InvalidStoredHash,
}

impl fmt::Display for PasswordError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            PasswordError::Hashing => write!(f, "Argon2id-Hashing fehlgeschlagen"),
            PasswordError::InvalidStoredHash => {
                write!(f, "gespeicherter Hash hat kein gültiges PHC-Format")
            }
        }
    }
}

impl std::error::Error for PasswordError {}

/// Argon2id mit expliziten Parametern (RFC 9106-Empfehlung für sicherheitskritische Anwendungen):
/// m_cost = 65536 KiB (64 MB), t_cost = 3, p_cost = 1. Die Default-Parameter der Crate
/// (m_cost = 19 MB, t_cost = 2) sind für PIN-Hashing zu schwach — der Suchraum einer 4-stelligen
/// PIN ist nur 10^4, und 2 Iterationen über 19 MB bremsen Brute-Force auf GPU-Clustern nicht
/// ausreichend. Die Parameter stehen im PHC-String, sodass alte Hashes mit Default-Parametern
/// weiterhin verifiziert werden (Argon2id liest die Parameter aus dem Hash, nicht aus dem
/// Argon2-Objekt — `verify_password` verwendet die im Hash gespeicherten Parameter).
fn argon2_instance() -> Argon2<'static> {
    let params =
        Params::new(65536, 3, 1, None).expect("Argon2id-Parameter sind konstant und gültig");
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
}

/// Hasht `password` mit Argon2id (RFC-9106-Parameter: 64 MB / 3 Iterationen) und einem
/// frischen Zufalls-Salt. `password` wird hier nicht genullt — das bleibt Sache des Aufrufers
/// (Kotlin-seitiger PIN-Puffer, Konzept Abschnitt 16: "kein Autofill/Logging der PIN").
///
/// Salt-Erzeugung läuft seit argon2 0.6 intern über die einargumentige `hash_password`
/// (`PasswordHasher::hash_password`, Default-Feature "getrandom") — kein eigener
/// `SaltString`/`OsRng`-Umweg mehr nötig wie bis argon2 0.5.
#[uniffi::export]
pub fn hash_password(password: Vec<u8>) -> Result<PasswordHash, PasswordError> {
    let argon2 = argon2_instance();
    let hash = argon2
        .hash_password(&password)
        .map_err(|_| PasswordError::Hashing)?;
    Ok(PasswordHash {
        phc: hash.to_string(),
    })
}

/// Prüft `password` gegen einen zuvor mit [hash_password] erzeugten Hash. Konstantzeit-Vergleich
/// ist Teil der `password-hash`-Crate-Implementierung (Konzept Abschnitt 6: "Konstantzeit-
/// Vergleich" für die Anti-Hammering-Schicht).
#[uniffi::export]
pub fn verify_password(password: Vec<u8>, hash: PasswordHash) -> Result<bool, PasswordError> {
    let parsed =
        Argon2PasswordHash::new(&hash.phc).map_err(|_| PasswordError::InvalidStoredHash)?;
    let argon2 = Argon2::default();
    Ok(argon2.verify_password(&password, &parsed).is_ok())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hash_and_verify_round_trip() {
        let password = b"korrektes-pferd-batterie-heftklammer".to_vec();
        let hash = hash_password(password.clone()).expect("hash");

        assert!(verify_password(password, hash).expect("verify"));
    }

    #[test]
    fn verify_rejects_wrong_password() {
        let hash = hash_password(b"richtiges-passwort".to_vec()).expect("hash");
        assert!(!verify_password(b"falsches-passwort".to_vec(), hash).expect("verify"));
    }

    #[test]
    fn hash_uses_argon2id_variant() {
        let hash = hash_password(b"pin1234".to_vec()).expect("hash");
        assert!(
            hash.as_str().starts_with("$argon2id$"),
            "muss Argon2id sein (nicht Argon2i/Argon2d), Konzept Abschnitt 3/10a"
        );
    }

    #[test]
    fn hashes_of_same_password_differ_due_to_random_salt() {
        let password = b"gleiches-passwort".to_vec();
        let a = hash_password(password.clone()).expect("hash a");
        let b = hash_password(password.clone()).expect("hash b");

        assert_ne!(
            a.as_str(),
            b.as_str(),
            "Salt muss pro Aufruf neu zufällig sein"
        );
        assert!(verify_password(password.clone(), a).expect("verify a"));
        assert!(verify_password(password, b).expect("verify b"));
    }

    #[test]
    fn verify_rejects_corrupted_stored_hash() {
        let corrupted = PasswordHash {
            phc: "nicht-im-phc-format".to_string(),
        };
        let result = verify_password(b"irgendein-passwort".to_vec(), corrupted);
        assert!(matches!(result, Err(PasswordError::InvalidStoredHash)));
    }
}
