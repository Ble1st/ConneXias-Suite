//! Zufällige DEK-Erzeugung (Data-Encryption-Key, Konzept Abschnitt 10: "DEK (AES-256-GCM)
//! verschlüsselt Daten"). Bewusst zufällig statt deterministisch über [crate::kdf::derive_key]
//! abgeleitet — jede Envelope-Instanz braucht einen eigenen, unabhängigen Schlüssel; `derive_key`
//! bleibt für zweckgebundene Ableitung aus einem Master-Secret reserviert (Konzept 10:
//! "Strikte Schlüsseltrennung pro App, HKDF-`info` zweckgebunden").

use argon2::password_hash::rand_core::{OsRng, RngCore};
use zeroize::Zeroizing;

/// Erzeugt `length` kryptografisch zufällige Bytes (`OsRng`, dieselbe CSPRNG-Quelle wie
/// Argon2-Salts in [crate::password] und AEAD-Nonces in [crate::aead] — ein Zufallsquellen-Pfad
/// für die ganze Engine).
///
/// Der Zwischenpuffer ist [Zeroizing] — Meilenstein B.3 ist der erste Punkt, an dem die Engine
/// ein echtes Geheimnis in der Hand hält (B.1-Notiz in [crate::aead]/[crate::password]: "echte
/// Geheimnisse kommen erst mit B.3"). Das schützt nur Rusts eigene Kopie bei frühem
/// Return/Panic vor dem Verlassen dieser Funktion — der Rückgabewert selbst muss als
/// `Vec<u8>` die FFI-Grenze überqueren (UniFFI kennt keinen zeroisierenden Rückgabetyp) und ist
/// ab dort nicht mehr kontrollierbar; siehe Kotlin-seitige Pufferbehandlung in `KeystoreKek`.
#[uniffi::export]
pub fn generate_dek(length: u32) -> Vec<u8> {
    let mut buf = Zeroizing::new(vec![0u8; length as usize]);
    OsRng.fill_bytes(&mut buf);
    buf.to_vec()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generate_dek_has_requested_length() {
        let dek = generate_dek(32);
        assert_eq!(dek.len(), 32);
    }

    #[test]
    fn generate_dek_is_random_per_call() {
        let a = generate_dek(32);
        let b = generate_dek(32);
        assert_ne!(a, b, "zwei Aufrufe dürfen nie denselben DEK liefern");
    }

    #[test]
    fn generate_dek_supports_zero_length() {
        let dek = generate_dek(0);
        assert!(dek.is_empty());
    }
}
