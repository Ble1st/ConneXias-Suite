//! Ed25519 — asymmetrische Signaturen für den Offline-Failsafe (Meilenstein D.1/D.2, Konzept
//! Abschnitt 9/10a: "X25519/Ed25519/P-256 kommen mit D.2 für den Offline-Failsafe").
//!
//! **Schlüsselerzeugung und Signieren laufen NIE auf dem Gerät** (Konzept 9: "privater Schlüssel
//! offline ... nie auf dem Gerät"). [generate_signing_keypair]/[sign_message] existieren hier
//! trotzdem als Teil der "gemeinsamen Engine" (10a: eine Krypto-Codebasis statt einer zweiten,
//! separat gepflegten Implementierung) — genutzt vom Offline-Tool
//! (`rust/engine/src/bin/failsafe_keytool.rs`), das auf einer Air-Gap-Maschine läuft, **nicht**
//! von Warden selbst. Warden auf dem Gerät ruft ausschließlich [verify_signature] auf.
//!
//! **Challenge-Erzeugung** ([generate_challenge]) läuft dagegen bewusst auf dem Gerät — Warden
//! erzeugt die Challenge, zeigt sie an, der Besitzer signiert sie offline, die Response wird
//! zurück eingegeben (Konzept Abschnitt 9: "Challenge auf dem Gerät erzeugt/angezeigt, Response
//! offline berechnet, lokal eingegeben"). Wiederverwendet denselben CSPRNG-Pfad wie
//! [crate::dek::generate_dek] (`OsRng`) — kein zweiter Zufallsquellen-Pfad in der Engine.

use crate::dek::generate_dek;
use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use rand_core::OsRng;
use std::fmt;

pub const PUBLIC_KEY_LEN: usize = 32;
pub const SECRET_KEY_LEN: usize = 32;
pub const SIGNATURE_LEN: usize = 64;

#[derive(Debug, uniffi::Error)]
pub enum SigningError {
    /// `secret_key`/`public_key` hat nicht die für Ed25519 erforderliche Länge (32 Byte) —
    /// oder ein `public_key` ist zwar 32 Byte lang, aber kein gültiger Ed25519-Kurvenpunkt.
    InvalidKeyLength,
    /// `signature` hat nicht die für Ed25519 erforderliche Länge (64 Byte).
    InvalidSignatureLength,
}

impl fmt::Display for SigningError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SigningError::InvalidKeyLength => {
                write!(
                    f,
                    "Schlüssel muss {PUBLIC_KEY_LEN} Byte lang sein (Ed25519)"
                )
            }
            SigningError::InvalidSignatureLength => {
                write!(f, "Signatur muss {SIGNATURE_LEN} Byte lang sein (Ed25519)")
            }
        }
    }
}

impl std::error::Error for SigningError {}

/// Ed25519-Schlüsselpaar. **Nur vom Offline-Tool erzeugt, nie auf dem Gerät** (s. Moduldoc).
///
/// Kein `Debug`-Derive: `secret_key` ist ein echtes Geheimnis (dasselbe Muster wie
/// [crate::keystore::GeneratedDek]) — ein handgeschriebener [fmt::Debug] redigiert es unten.
#[derive(Clone, uniffi::Record)]
pub struct SigningKeyPair {
    pub public_key: Vec<u8>,
    pub secret_key: Vec<u8>,
}

impl fmt::Debug for SigningKeyPair {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SigningKeyPair")
            .field("public_key", &self.public_key)
            .field("secret_key", &"<redacted>")
            .finish()
    }
}

/// Erzeugt ein frisches Ed25519-Schlüsselpaar. **Nie von Warden selbst aufgerufen** — nur vom
/// Offline-Tool auf der Air-Gap-Maschine (Konzept Abschnitt 9, Meilenstein D.1).
#[uniffi::export]
pub fn generate_signing_keypair() -> SigningKeyPair {
    let mut csprng = OsRng;
    let signing_key = SigningKey::generate(&mut csprng);
    SigningKeyPair {
        public_key: signing_key.verifying_key().to_bytes().to_vec(),
        secret_key: signing_key.to_bytes().to_vec(),
    }
}

/// Signiert `message` mit `secret_key`. **Nie von Warden selbst aufgerufen** — nur vom
/// Offline-Tool (s. Moduldoc); existiert hier, damit Keygen/Sign/Verify dieselbe
/// Ed25519-Implementierung teilen statt einer zweiten, separat gepflegten Kopie im Offline-Tool.
#[uniffi::export]
pub fn sign_message(secret_key: Vec<u8>, message: Vec<u8>) -> Result<Vec<u8>, SigningError> {
    let bytes: [u8; SECRET_KEY_LEN] = secret_key
        .as_slice()
        .try_into()
        .map_err(|_| SigningError::InvalidKeyLength)?;
    let signing_key = SigningKey::from_bytes(&bytes);
    let signature = signing_key.sign(&message);
    Ok(signature.to_bytes().to_vec())
}

/// Prüft `signature` über `message` gegen `public_key`. Die einzige der Signing-Funktionen, die
/// tatsächlich auf dem Gerät läuft (Warden hält nur den öffentlichen Schlüssel, s. Moduldoc).
///
/// Gibt `Ok(false)` für eine wohlgeformte, aber falsche Signatur zurück (analog zu
/// [crate::password::verify_password]) — nur strukturell ungültige Eingaben (falsche
/// Schlüssel-/Signaturlänge, kein gültiger Kurvenpunkt) sind ein [SigningError]. Aufrufer müssen
/// **beide** Fälle (Err und `Ok(false)`) einheitlich als "nicht verifiziert" behandeln
/// (Fail-Safe, Invariante 6) — s. `OfflineFailsafeVerifier` (`:core:crypto`).
#[uniffi::export]
pub fn verify_signature(
    public_key: Vec<u8>,
    message: Vec<u8>,
    signature: Vec<u8>,
) -> Result<bool, SigningError> {
    let key_bytes: [u8; PUBLIC_KEY_LEN] = public_key
        .as_slice()
        .try_into()
        .map_err(|_| SigningError::InvalidKeyLength)?;
    let verifying_key =
        VerifyingKey::from_bytes(&key_bytes).map_err(|_| SigningError::InvalidKeyLength)?;
    let sig_bytes: [u8; SIGNATURE_LEN] = signature
        .as_slice()
        .try_into()
        .map_err(|_| SigningError::InvalidSignatureLength)?;
    let sig = Signature::from_bytes(&sig_bytes);
    Ok(verifying_key.verify(&message, &sig).is_ok())
}

/// Erzeugt `length` kryptografisch zufällige Bytes als Failsafe-Challenge (Konzept Abschnitt 9,
/// Meilenstein D.2/D.3) — reine Namensklarheit über [crate::dek::generate_dek] hinaus
/// (dieselbe Implementierung, aber ein DEK und eine Challenge sind fachlich verschiedene Dinge;
/// ein Aufrufer, der `generate_dek()` für eine Challenge aufriefe, würde das verwischen).
#[uniffi::export]
pub fn generate_challenge(length: u32) -> Vec<u8> {
    generate_dek(length)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sign_verify_round_trip() {
        let pair = generate_signing_keypair();
        let message = b"warden:failsafe:challenge:v1:deadbeef".to_vec();

        let signature = sign_message(pair.secret_key, message.clone()).expect("sign");
        let verified = verify_signature(pair.public_key, message, signature).expect("verify");

        assert!(verified, "gültige Signatur muss verifizieren");
    }

    #[test]
    fn verify_rejects_wrong_message() {
        let pair = generate_signing_keypair();
        let signature = sign_message(pair.secret_key, b"echte-challenge".to_vec()).expect("sign");

        let verified = verify_signature(pair.public_key, b"andere-challenge".to_vec(), signature)
            .expect("verify");

        assert!(
            !verified,
            "Signatur über andere Nachricht darf nicht verifizieren"
        );
    }

    #[test]
    fn verify_rejects_wrong_public_key() {
        let pair_a = generate_signing_keypair();
        let pair_b = generate_signing_keypair();
        let message = b"challenge".to_vec();

        let signature = sign_message(pair_a.secret_key, message.clone()).expect("sign");
        let verified = verify_signature(pair_b.public_key, message, signature).expect("verify");

        assert!(
            !verified,
            "Signatur muss an ihr eigenes Schlüsselpaar gebunden sein"
        );
    }

    #[test]
    fn verify_rejects_tampered_signature() {
        let pair = generate_signing_keypair();
        let message = b"challenge".to_vec();
        let mut signature = sign_message(pair.secret_key, message.clone()).expect("sign");
        signature[0] ^= 0xFF;

        let verified = verify_signature(pair.public_key, message, signature).expect("verify");

        assert!(!verified, "manipulierte Signatur darf nicht verifizieren");
    }

    #[test]
    fn sign_rejects_wrong_secret_key_length() {
        let result = sign_message(vec![0u8; 16], b"msg".to_vec());
        assert!(matches!(result, Err(SigningError::InvalidKeyLength)));
    }

    #[test]
    fn verify_rejects_wrong_public_key_length() {
        let signature = vec![0u8; SIGNATURE_LEN];
        let result = verify_signature(vec![0u8; 16], b"msg".to_vec(), signature);
        assert!(matches!(result, Err(SigningError::InvalidKeyLength)));
    }

    #[test]
    fn verify_rejects_wrong_signature_length() {
        let pair = generate_signing_keypair();
        let result = verify_signature(pair.public_key, b"msg".to_vec(), vec![0u8; 10]);
        assert!(matches!(result, Err(SigningError::InvalidSignatureLength)));
    }

    #[test]
    fn keypairs_are_random_per_call() {
        let a = generate_signing_keypair();
        let b = generate_signing_keypair();
        assert_ne!(a.public_key, b.public_key);
        assert_ne!(a.secret_key, b.secret_key);
    }

    #[test]
    fn generate_challenge_has_requested_length_and_is_random() {
        let a = generate_challenge(32);
        let b = generate_challenge(32);
        assert_eq!(a.len(), 32);
        assert_ne!(a, b, "zwei Challenges dürfen nie identisch sein");
    }

    #[test]
    fn signing_keypair_debug_redacts_secret_key() {
        let pair = SigningKeyPair {
            public_key: vec![0x11; PUBLIC_KEY_LEN],
            secret_key: vec![0x42; SECRET_KEY_LEN],
        };
        let debug_output = format!("{pair:?}");
        assert!(!debug_output.contains("66")); // 0x42 == 66 dezimal
        assert!(debug_output.contains("redacted"));
    }
}
