//! `connexias-engine` — gemeinsame Rust-Krypto-Engine (Konzept Abschnitt 10a).
//!
//! Cargo-Workspace-Library, per UniFFI in jede App gelinkt (Meilenstein B.2) — kein
//! Krypto-IPC-Dienst, kein SPOF, kein Schlüssel über Binder.
//!
//! **Meilenstein B.1:** AES-256-GCM (AEAD), HKDF-SHA256, Argon2id, `zeroize`.
//! **Meilenstein B.2:** UniFFI-Bindings (Kotlin), Round-Trip seal/open aus Kotlin.
//! **Meilenstein B.3:** DEK-Erzeugung + KEK-Wrap/Unwrap-Callback (Kotlin implementiert
//! `KeyWrapper` gegen den AndroidKeyStore, Konzept 10a — kein NDK-Pfad zum Keystore, daher
//! Callback statt direkter Rust-Implementierung).
//! **Meilenstein D.1/D.2 (dieser Stand):** erste asymmetrische Krypto — Ed25519
//! (Schlüsselerzeugung/Signieren/Verifikation) für den Offline-Failsafe (Konzept Abschnitt 9).
//! X25519/P-256 weiterhin nicht benötigt (kein Schlüsselaustausch/keine weitere Kurve in Sicht).

pub mod aead;
pub mod dek;
pub mod kdf;
pub mod keystore;
pub mod password;
pub mod signing;

pub use aead::{Envelope, OpenError, SealError, open, seal};
pub use dek::generate_dek;
pub use kdf::derive_key;
pub use keystore::{GeneratedDek, KeyWrapper, WrapError, generate_and_wrap_dek, unwrap_dek};
pub use password::{PasswordHash, hash_password, verify_password};
pub use signing::{
    SigningError, SigningKeyPair, generate_challenge, generate_signing_keypair, sign_message,
    verify_signature,
};

uniffi::setup_scaffolding!();
