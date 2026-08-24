//! HKDF-SHA256 — Schlüsselableitung mit zweckgebundenem `info`-Parameter (Konzept Abschnitt 10:
//! "strikte Schlüsseltrennung pro App, HKDF-`info` zweckgebunden; keine App teilt ihren KEK").

use hkdf::Hkdf;
use sha2::Sha256;
use std::fmt;

#[derive(Debug, uniffi::Error)]
pub enum KdfError {
    /// HKDF-Expand ist nur für `length <= 255 * HashLen` (hier: 255 * 32 Byte) definiert.
    OutputTooLong,
}

impl fmt::Display for KdfError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            KdfError::OutputTooLong => {
                write!(f, "angeforderte Ausgabelänge überschreitet HKDF-Grenze")
            }
        }
    }
}

impl std::error::Error for KdfError {}

/// Leitet `length` Bytes Schlüsselmaterial aus `input_key_material` ab. `salt` sollte zufällig
/// und pro Kontext eindeutig sein (kann leer sein, dann wird ein Null-Salt der Hash-Länge
/// verwendet — HKDF-Standardverhalten); `info` bindet den Zweck (z. B. `b"warden:registry:kek"`)
/// und verhindert, dass aus demselben Eingangsmaterial versehentlich derselbe Schlüssel für
/// zwei verschiedene Zwecke entsteht.
///
/// `length` ist `u32` statt `usize` — UniFFI-Exports (Meilenstein B.2) brauchen plattform-
/// unabhängige Breiten, `usize` ist dafür nicht geeignet.
#[uniffi::export]
pub fn derive_key(
    input_key_material: Vec<u8>,
    salt: Vec<u8>,
    info: Vec<u8>,
    length: u32,
) -> Result<Vec<u8>, KdfError> {
    let hk = Hkdf::<Sha256>::new(Some(&salt), &input_key_material);
    let mut output = vec![0u8; length as usize];
    hk.expand(&info, &mut output)
        .map_err(|_| KdfError::OutputTooLong)?;
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn derive_key_is_deterministic() {
        let ikm = b"geteiltes-eingangsmaterial".to_vec();
        let salt = b"salt-123".to_vec();
        let info = b"warden:registry:kek".to_vec();

        let a = derive_key(ikm.clone(), salt.clone(), info.clone(), 32).expect("derive a");
        let b = derive_key(ikm, salt, info, 32).expect("derive b");

        assert_eq!(
            a, b,
            "HKDF muss deterministisch sein — gleiche Eingaben, gleiche Ausgabe"
        );
    }

    #[test]
    fn different_info_yields_different_keys() {
        let ikm = b"geteiltes-eingangsmaterial".to_vec();
        let salt = b"salt-123".to_vec();

        let registry_key = derive_key(
            ikm.clone(),
            salt.clone(),
            b"warden:registry:kek".to_vec(),
            32,
        )
        .expect("derive");
        let log_key = derive_key(ikm, salt, b"warden:log:kek".to_vec(), 32).expect("derive");

        assert_ne!(
            registry_key, log_key,
            "zweckgebundenes info muss unterschiedliche Schlüssel erzeugen (Konzept 10)"
        );
    }

    #[test]
    fn different_salt_yields_different_keys() {
        let ikm = b"geteiltes-eingangsmaterial".to_vec();
        let info = b"warden:registry:kek".to_vec();

        let a = derive_key(ikm.clone(), b"salt-a".to_vec(), info.clone(), 32).expect("derive a");
        let b = derive_key(ikm, b"salt-b".to_vec(), info, 32).expect("derive b");

        assert_ne!(a, b);
    }

    #[test]
    fn output_length_is_respected() {
        let key =
            derive_key(b"ikm".to_vec(), b"salt".to_vec(), b"info".to_vec(), 64).expect("derive");
        assert_eq!(key.len(), 64);
    }

    #[test]
    fn rejects_excessive_output_length() {
        // HKDF-Grenze: 255 * 32 (SHA-256-Ausgabelänge) = 8160 Byte
        let result = derive_key(
            b"ikm".to_vec(),
            b"salt".to_vec(),
            b"info".to_vec(),
            255 * 32 + 1,
        );
        assert!(matches!(result, Err(KdfError::OutputTooLong)));
    }
}
