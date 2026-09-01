//! AES-256-GCM (AEAD) — Konzept Abschnitt 10a: `seal(context, plaintext)`/`open(context, envelope)`
//! mit `context` als AAD (Additional Authenticated Data), nie Teil des Ciphertexts.

use aes_gcm::aead::{Aead, Generate, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Key, Nonce};
use std::fmt;

pub const KEY_LEN: usize = 32;
pub const NONCE_LEN: usize = 12;

/// Verschlüsselter Datensatz: Nonce + Ciphertext (das GCM-Auth-Tag ist im Ciphertext enthalten,
/// Standardverhalten der `aead`-Crate — kein separates Tag-Feld nötig).
///
/// `nonce` ist `Vec<u8>` statt `[u8; NONCE_LEN]` — UniFFI-Records (Meilenstein B.2) brauchen
/// FFI-freundliche Typen; die Länge wird trotzdem intern über [NONCE_LEN] durchgesetzt.
///
/// Kein eigenes `Drop`/Zeroize (anders als in B.1): UniFFIs `Record`-Derive verschiebt Felder
/// beim FFI-Übergang, das verträgt sich nicht mit `Drop`. Unkritisch — der Ciphertext ist per
/// Definition öffentlich (verschlüsselt), kein Geheimnis, das gelöscht werden müsste.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Envelope {
    pub nonce: Vec<u8>,
    pub ciphertext: Vec<u8>,
}

#[derive(Debug, uniffi::Error)]
pub enum SealError {
    InvalidKeyLength,
    Encryption,
}

#[derive(Debug, uniffi::Error)]
pub enum OpenError {
    InvalidKeyLength,
    /// Envelope hat nicht die erwartete Nonce-Länge — kann nicht aus einem gültigen `seal()`
    /// stammen (Manipulation oder Datenkorruption).
    InvalidEnvelope,
    /// Entschlüsselung/Auth-Tag-Prüfung fehlgeschlagen — Konzept Invariante 6 (Fail-Safe):
    /// Aufrufer müssen das als "gesperrt bleiben", nie als "leer/offen" behandeln.
    Decryption,
}

impl fmt::Display for SealError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SealError::InvalidKeyLength => write!(f, "Schlüssel muss {KEY_LEN} Byte lang sein"),
            SealError::Encryption => write!(f, "Verschlüsselung fehlgeschlagen"),
        }
    }
}

impl fmt::Display for OpenError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            OpenError::InvalidKeyLength => write!(f, "Schlüssel muss {KEY_LEN} Byte lang sein"),
            OpenError::InvalidEnvelope => {
                write!(
                    f,
                    "Envelope hat ungültige Nonce-Länge (erwartet {NONCE_LEN} Byte)"
                )
            }
            OpenError::Decryption => write!(f, "Entschlüsselung/Authentifizierung fehlgeschlagen"),
        }
    }
}

impl std::error::Error for SealError {}
impl std::error::Error for OpenError {}

/// Verschlüsselt `plaintext` mit AES-256-GCM. `context` ist die AAD (Zweck-/Empfänger-Bindung,
/// Konzept Abschnitt 2/10) — wird authentifiziert, aber nicht verschlüsselt/gespeichert im
/// Ciphertext; muss bei `open()` identisch erneut übergeben werden.
#[uniffi::export]
pub fn seal(key: Vec<u8>, context: Vec<u8>, plaintext: Vec<u8>) -> Result<Envelope, SealError> {
    if key.len() != KEY_LEN {
        return Err(SealError::InvalidKeyLength);
    }
    let key =
        Key::<Aes256Gcm>::try_from(key.as_slice()).map_err(|_| SealError::InvalidKeyLength)?;
    let cipher = Aes256Gcm::new(&key);
    let nonce = Nonce::generate();

    let ciphertext = cipher
        .encrypt(
            &nonce,
            Payload {
                msg: &plaintext,
                aad: &context,
            },
        )
        .map_err(|_| SealError::Encryption)?;

    Ok(Envelope {
        nonce: nonce.to_vec(),
        ciphertext,
    })
}

/// Entschlüsselt ein [Envelope]. Bei falschem Schlüssel, falscher/fehlender AAD oder
/// manipuliertem Ciphertext schlägt dies fehl — Aufrufer müssen das als Fail-Safe-Signal
/// behandeln (Invariante 6: gesperrt bleiben, nicht "leer" interpretieren).
#[uniffi::export]
pub fn open(key: Vec<u8>, context: Vec<u8>, envelope: Envelope) -> Result<Vec<u8>, OpenError> {
    if key.len() != KEY_LEN {
        return Err(OpenError::InvalidKeyLength);
    }
    if envelope.nonce.len() != NONCE_LEN {
        return Err(OpenError::InvalidEnvelope);
    }
    let key =
        Key::<Aes256Gcm>::try_from(key.as_slice()).map_err(|_| OpenError::InvalidKeyLength)?;
    let cipher = Aes256Gcm::new(&key);
    let nonce =
        Nonce::try_from(envelope.nonce.as_slice()).map_err(|_| OpenError::InvalidEnvelope)?;

    cipher
        .decrypt(
            &nonce,
            Payload {
                msg: &envelope.ciphertext,
                aad: &context,
            },
        )
        .map_err(|_| OpenError::Decryption)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn test_key() -> Vec<u8> {
        vec![0x42; KEY_LEN]
    }

    #[test]
    fn seal_open_round_trip() {
        let key = test_key();
        let context = b"warden:registry:v1".to_vec();
        let plaintext = b"Safeguard-Registry-Sollzustand".to_vec();

        let envelope = seal(key.clone(), context.clone(), plaintext.clone()).expect("seal");
        let recovered = open(key, context, envelope).expect("open");

        assert_eq!(recovered, plaintext);
    }

    #[test]
    fn open_fails_with_wrong_key() {
        let key = test_key();
        let mut wrong_key = test_key();
        wrong_key[0] ^= 0xFF;
        let context = b"warden:registry:v1".to_vec();

        let envelope = seal(key, context.clone(), b"geheim".to_vec()).expect("seal");
        let result = open(wrong_key, context, envelope);

        assert!(matches!(result, Err(OpenError::Decryption)));
    }

    #[test]
    fn open_fails_with_wrong_context_aad() {
        let key = test_key();
        let envelope = seal(key.clone(), b"context-a".to_vec(), b"geheim".to_vec()).expect("seal");
        let result = open(key, b"context-b".to_vec(), envelope);

        assert!(matches!(result, Err(OpenError::Decryption)));
    }

    #[test]
    fn open_fails_on_tampered_ciphertext() {
        let key = test_key();
        let context = b"warden:log:v1".to_vec();
        let mut envelope =
            seal(key.clone(), context.clone(), b"Log-Eintrag".to_vec()).expect("seal");

        let last = envelope.ciphertext.len() - 1;
        envelope.ciphertext[last] ^= 0x01;

        let result = open(key, context, envelope);
        assert!(matches!(result, Err(OpenError::Decryption)));
    }

    #[test]
    fn rejects_wrong_key_length() {
        let short_key = vec![0u8; 16];
        let result = seal(short_key, b"ctx".to_vec(), b"data".to_vec());
        assert!(matches!(result, Err(SealError::InvalidKeyLength)));
    }

    #[test]
    fn nonces_are_unique_per_call() {
        let key = test_key();
        let a = seal(key.clone(), b"ctx".to_vec(), b"gleicher Klartext".to_vec()).expect("seal a");
        let b = seal(key, b"ctx".to_vec(), b"gleicher Klartext".to_vec()).expect("seal b");
        assert_ne!(
            a.nonce, b.nonce,
            "Nonce-Wiederverwendung wäre bei GCM katastrophal"
        );
    }

    #[test]
    fn open_fails_on_wrong_nonce_length() {
        let key = test_key();
        let bad_envelope = Envelope {
            nonce: vec![0u8; 4],
            ciphertext: vec![0u8; 16],
        };
        let result = open(key, b"ctx".to_vec(), bad_envelope);
        assert!(matches!(result, Err(OpenError::InvalidEnvelope)));
    }
}
