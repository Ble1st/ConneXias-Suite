//! KEK-Wrap/Unwrap-Callback (Konzept 10a/12, Meilenstein B.3): "kein NDK-Pfad zum AndroidKeyStore
//! → Schlüsselerzeugung/-wrapping über Java-JCA (Kotlin) via JNI ... Rust erhält nur kurzlebige,
//! zeroized DEKs bzw. ruft Wrap/Unwrap per JNI-Callback." Der KEK selbst (TEE-/StrongBox-
//! gebunden, `KeystoreKek` in `:core:crypto`) entsteht und bleibt vollständig in Kotlin/dem
//! AndroidKeyStore-Provider — Rust sieht ihn nie, nur den kurzlebigen DEK und dessen Wrap-Form.
//!
//! Technisch eine UniFFI-"Callback-Interface" (`#[uniffi::export(foreign)]` auf einem Trait):
//! Kotlin implementiert den Trait, Rust ruft ihn synchron auf. Das ist die von UniFFI
//! bereitgestellte Entsprechung zum in Konzept 10a beschriebenen "JNI-Callback"-Muster.

use crate::aead::Envelope;
use crate::dek::generate_dek;
use std::fmt;
use std::sync::Arc;

#[derive(Debug, uniffi::Error)]
pub enum WrapError {
    /// Kotlin-seitige Wrap/Unwrap-Operation ist fehlgeschlagen — z. B. Keystore-Schlüssel nicht
    /// verfügbar/invalidiert (Lockscreen-/Biometrie-Änderung invalidiert Keystore-Keys je nach
    /// Spec), oder beim Unwrap die GCM-Auth-Tag-Prüfung nicht bestanden. Fail-Safe
    /// (Invariante 6): Aufrufer behandeln jeden Fehlerfall einheitlich als "nicht entsperrbar",
    /// nie als Sonderfall, der in offenen Zustand führen könnte.
    Failed,
}

impl fmt::Display for WrapError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "KEK-Wrap/Unwrap (Keystore-Callback) fehlgeschlagen")
    }
}

impl std::error::Error for WrapError {}

/// Von Kotlin implementiert (`KeystoreKek`, `:core:crypto`): wrappt/unwrappt einen DEK mit dem
/// TEE-/StrongBox-gebundenen KEK über `javax.crypto.Cipher` + `AndroidKeyStore`-Provider.
/// Wrap-Ergebnis ist strukturell identisch zu einem AEAD-[Envelope] (Nonce + Ciphertext inkl.
/// GCM-Auth-Tag) — kein separater Record-Typ nötig, es *ist* eine AES-GCM-Verschlüsselung,
/// nur mit einem hardware-gebundenen statt einem Kotlin-seitig sichtbaren Schlüssel.
#[uniffi::export(foreign)]
pub trait KeyWrapper: Send + Sync {
    fn wrap(&self, dek: Vec<u8>) -> Result<Envelope, WrapError>;
    fn unwrap(&self, wrapped: Envelope) -> Result<Vec<u8>, WrapError>;
}

/// Ergebnis von [generate_and_wrap_dek]: der rohe DEK (sofort verwendbar mit
/// [crate::aead::seal]/[crate::aead::open]) zusammen mit seiner gewrappten Form (das, was
/// tatsächlich persistiert wird — der rohe DEK selbst wird nie gespeichert).
///
/// Kein `Debug`-Derive: `dek` ist echtes Geheimnis (anders als [Envelope]/[crate::PasswordHash]
/// in B.1/B.2) — versehentliches Logging über `{:?}` würde ihn im Klartext in Logcat/Crash-
/// Reports streuen. Handgeschriebener [fmt::Debug] unten redigiert das Feld bewusst.
#[derive(Clone, uniffi::Record)]
pub struct GeneratedDek {
    pub dek: Vec<u8>,
    pub wrapped: Envelope,
}

impl fmt::Debug for GeneratedDek {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("GeneratedDek")
            .field("dek", &"<redacted>")
            .field("wrapped", &self.wrapped)
            .finish()
    }
}

/// Erzeugt einen frischen, zufälligen DEK ([generate_dek]) und wrappt ihn sofort über den
/// übergebenen [KeyWrapper] mit dem Keystore-KEK.
#[uniffi::export]
pub fn generate_and_wrap_dek(
    wrapper: Arc<dyn KeyWrapper>,
    length: u32,
) -> Result<GeneratedDek, WrapError> {
    let dek = generate_dek(length);
    let wrapped = wrapper.wrap(dek.clone())?;
    Ok(GeneratedDek { dek, wrapped })
}

/// Entwrappt einen zuvor gespeicherten DEK über den Keystore-KEK.
#[uniffi::export]
pub fn unwrap_dek(wrapper: Arc<dyn KeyWrapper>, wrapped: Envelope) -> Result<Vec<u8>, WrapError> {
    wrapper.unwrap(wrapped)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    /// Test-Double für [KeyWrapper] — simuliert den Keystore-KEK rein in Rust (XOR mit einem
    /// festen "Schlüssel", nur zum Verifizieren des Aufruf-Flusses, keine echte Kryptografie).
    /// Der echte KEK-Pfad (AndroidKeyStore, TEE/StrongBox) ist nur auf echtem Gerät testbar —
    /// analog zu [crate]'s UniFFI-Grenze selbst (Konzept-19-Muster: Rust-Unit-Tests prüfen die
    /// Logik, der Instrumented-Test auf dem Gerät prüft die echte Anbindung).
    struct FakeKeystore {
        fail_next: Mutex<bool>,
    }

    impl FakeKeystore {
        fn new() -> Self {
            Self {
                fail_next: Mutex::new(false),
            }
        }

        fn failing() -> Self {
            Self {
                fail_next: Mutex::new(true),
            }
        }
    }

    impl KeyWrapper for FakeKeystore {
        fn wrap(&self, dek: Vec<u8>) -> Result<Envelope, WrapError> {
            if *self.fail_next.lock().unwrap() {
                return Err(WrapError::Failed);
            }
            Ok(Envelope {
                nonce: vec![0u8; 12],
                ciphertext: dek.iter().map(|b| b ^ 0xAA).collect(),
            })
        }

        fn unwrap(&self, wrapped: Envelope) -> Result<Vec<u8>, WrapError> {
            if *self.fail_next.lock().unwrap() {
                return Err(WrapError::Failed);
            }
            Ok(wrapped.ciphertext.iter().map(|b| b ^ 0xAA).collect())
        }
    }

    #[test]
    fn generate_and_wrap_returns_usable_raw_dek() {
        let wrapper: Arc<dyn KeyWrapper> = Arc::new(FakeKeystore::new());
        let generated = generate_and_wrap_dek(wrapper, 32).expect("generate_and_wrap_dek");

        assert_eq!(generated.dek.len(), 32);
        assert_ne!(
            generated.dek, generated.wrapped.ciphertext,
            "gewrappte Form muss sich vom Klartext-DEK unterscheiden"
        );
    }

    #[test]
    fn wrap_then_unwrap_round_trips_to_original_dek() {
        let wrapper: Arc<dyn KeyWrapper> = Arc::new(FakeKeystore::new());
        let generated = generate_and_wrap_dek(wrapper.clone(), 32).expect("generate_and_wrap_dek");

        let recovered = unwrap_dek(wrapper, generated.wrapped).expect("unwrap_dek");
        assert_eq!(recovered, generated.dek);
    }

    #[test]
    fn wrap_failure_propagates_as_wrap_error() {
        let wrapper: Arc<dyn KeyWrapper> = Arc::new(FakeKeystore::failing());
        let result = generate_and_wrap_dek(wrapper, 32);
        assert!(matches!(result, Err(WrapError::Failed)));
    }

    #[test]
    fn unwrap_failure_propagates_as_wrap_error() {
        let wrapper: Arc<dyn KeyWrapper> = Arc::new(FakeKeystore::failing());
        let bad_envelope = Envelope {
            nonce: vec![0u8; 12],
            ciphertext: vec![0u8; 32],
        };
        let result = unwrap_dek(wrapper, bad_envelope);
        assert!(matches!(result, Err(WrapError::Failed)));
    }

    #[test]
    fn generated_dek_debug_redacts_raw_key() {
        let generated = GeneratedDek {
            dek: vec![0x42; 32],
            wrapped: Envelope {
                nonce: vec![0u8; 12],
                ciphertext: vec![0u8; 32],
            },
        };
        let debug_output = format!("{generated:?}");
        assert!(!debug_output.contains("66")); // 0x42 == 66 dezimal
        assert!(debug_output.contains("redacted"));
    }
}
