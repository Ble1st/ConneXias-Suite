//! `failsafe-keytool` — Offline-Signierwerkzeug für Wardens kryptografischen Offline-Failsafe
//! (Meilenstein D.1, Konzept Abschnitt 9). **Läuft NIE auf dem Zielgerät** — gebaut auf einer
//! normalen, vernetzten Maschine, das fertige Binary dann per USB auf eine dauerhaft
//! netzwerklose Air-Gap-Maschine kopiert (analog zum Release-Signaturschlüssel, Konzept 0.4/0.5:
//! "Release-Signaturschlüssel offline auf Air-Gap-Maschine erzeugen"). Volles Runbook:
//! `tools/failsafe-offline/README.md`.
//!
//! Nutzt `connexias_engine` direkt als Rust-Library (kein UniFFI/FFI) — dieselbe
//! Ed25519-Implementierung wie Wardens On-Device-`verify_signature`, keine zweite, separat
//! gepflegte Kopie.
//!
//! ```text
//! failsafe-keytool generate
//! failsafe-keytool sign <secret_key_hex> <challenge_hex> <neue_geraete_pin>
//! ```
//!
//! **Das dritte `sign`-Argument kam am 2026-08-28 dazu** (aus der Code-/Sicherheitsanalyse) und
//! ist nicht optional: signiert wird seitdem nicht mehr die nackte Challenge, sondern
//! `TAG ‖ u16be(challenge.len()) ‖ challenge ‖ SHA-256(pin)` — dieselbe Bytefolge, die Wardens
//! `de.ble1st.warden.domain.failsafe.FailsafeResponseMessage` auf dem Gerät neu berechnet. Ohne
//! diese Bindung bewies eine Signatur nur "jemand mit dem Wartungsschlüssel wollte einen
//! Failsafe", nicht "… und zwar mit *dieser* neuen Geräte-PIN": wer eine gültige Antwort in die
//! Hände bekam, konnte sie innerhalb der Challenge-TTL mit einer beliebigen eigenen PIN erneut
//! einreichen.
//!
//! Die PIN selbst verlässt diese Maschine nicht — nur ihr Digest steckt in der signierten
//! Nachricht. Sie steht allerdings in der Shell-History und in der Prozessliste dieser Maschine;
//! das ist dieselbe Annahme, unter der `secret_key_hex` bereits als Argument übergeben wird (die
//! Air-Gap-Maschine gilt als vertrauenswürdig, s. Runbook).
//!
//! **Beide Enden müssen identisch bleiben.** Wer [`failsafe_response_message`] ändert, ändert
//! `FailsafeResponseMessage.build` mit — sonst ist der Failsafe unbenutzbar.
//!
//! Bewusst ohne Abhängigkeit auf `clap` o. ä. — zwei Unterbefehle, minimale Eingabevalidierung,
//! kein Grund für eine CLI-Parser-Bibliothek.

use connexias_engine::{generate_signing_keypair, sign_message};
use sha2::{Digest, Sha256};
use std::env;
use std::process::ExitCode;

/// Muss byteweise identisch zu `FailsafeResponseMessage.DOMAIN_TAG` in `:app` sein.
const DOMAIN_TAG: &[u8] = b"warden:failsafe:v2";

/// Spiegel von `de.ble1st.warden.domain.failsafe.FailsafeResponseMessage.build` — s. Modul-Doc
/// oben. Das Längenpräfix hält das Format auch dann eindeutig, wenn die Challenge irgendwann
/// nicht mehr 32 Byte lang ist.
fn failsafe_response_message(
    challenge: &[u8],
    new_device_credential: &str,
) -> Result<Vec<u8>, String> {
    let challenge_length = u16::try_from(challenge.len()).map_err(|_| {
        format!(
            "Challenge zu lang für 2-Byte-Längenpräfix ({} Byte)",
            challenge.len()
        )
    })?;
    let credential_digest = Sha256::digest(new_device_credential.as_bytes());

    let mut message =
        Vec::with_capacity(DOMAIN_TAG.len() + 2 + challenge.len() + credential_digest.len());
    message.extend_from_slice(DOMAIN_TAG);
    message.extend_from_slice(&challenge_length.to_be_bytes());
    message.extend_from_slice(challenge);
    message.extend_from_slice(&credential_digest);
    Ok(message)
}

fn main() -> ExitCode {
    let args: Vec<String> = env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("generate") => {
            run_generate();
            ExitCode::SUCCESS
        }
        Some("sign") => match (args.get(2), args.get(3), args.get(4)) {
            (Some(secret_key_hex), Some(challenge_hex), Some(new_device_credential)) => {
                match run_sign(secret_key_hex, challenge_hex, new_device_credential) {
                    Ok(()) => ExitCode::SUCCESS,
                    Err(message) => {
                        eprintln!("Fehler: {message}");
                        ExitCode::FAILURE
                    }
                }
            }
            _ => {
                eprintln!(
                    "Verwendung: failsafe-keytool sign <secret_key_hex> <challenge_hex> \
                     <neue_geraete_pin>\n\n\
                     Die neue Geraete-PIN gehoert seit 2026-08-28 mit in die Signatur und muss \
                     auf dem Geraet exakt so eingetippt werden."
                );
                ExitCode::FAILURE
            }
        },
        _ => {
            eprintln!(
                "Verwendung:\n  \
                 failsafe-keytool generate\n  \
                 failsafe-keytool sign <secret_key_hex> <challenge_hex> <neue_geraete_pin>\n\n\
                 NUR auf einer dauerhaft netzwerklosen Air-Gap-Maschine ausführen \
                 (s. tools/failsafe-offline/README.md)."
            );
            ExitCode::FAILURE
        }
    }
}

fn run_generate() {
    let pair = generate_signing_keypair();
    println!("== ConneXias Failsafe — neues Ed25519-Schlüsselpaar ==");
    println!();
    println!("public_key (hex):  {}", hex::encode(&pair.public_key));
    println!("secret_key (hex):  {}", hex::encode(&pair.secret_key));
    println!();
    println!("WICHTIG (Konzept Abschnitt 9):");
    println!("  * public_key: in Warden hinterlegen (Failsafe-Bildschirm) — kein Geheimnis.");
    println!("  * secret_key: NIEMALS auf ein vernetztes Gerät übertragen. Sofort per Shamir");
    println!("    Secret Sharing (2-von-3, externes Tool, z. B. `ssss-split`) aufteilen, jeden");
    println!("    Share an einem getrennten, sicheren Ort lagern, dann JEDE unaufgeteilte Kopie");
    println!("    (Bildschirmpuffer, Terminal-Scrollback, Zwischenablage) auf dieser Maschine");
    println!("    löschen. Details: tools/failsafe-offline/README.md.");
}

fn run_sign(
    secret_key_hex: &str,
    challenge_hex: &str,
    new_device_credential: &str,
) -> Result<(), String> {
    let secret_key = hex::decode(secret_key_hex)
        .map_err(|e| format!("secret_key ist kein gültiges Hex: {e}"))?;
    let challenge =
        hex::decode(challenge_hex).map_err(|e| format!("challenge ist kein gültiges Hex: {e}"))?;

    let message = failsafe_response_message(&challenge, new_device_credential)?;
    let signature =
        sign_message(secret_key, message).map_err(|e| format!("Signieren fehlgeschlagen: {e}"))?;

    println!("signature (hex):   {}", hex::encode(&signature));
    println!();
    println!("Diese Zeichenkette in Wardens Failsafe-Bildschirm als \"Response\" eingeben —");
    println!("und als neue Geräte-PIN GENAU dieselbe Zeichenkette wie hier übergeben, sonst");
    println!("verifiziert die Signatur nicht (die PIN ist Teil der signierten Nachricht).");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Hält das Format fest, an dem beide Enden hängen — ändert sich diese Länge/Struktur, muss
    /// `FailsafeResponseMessage.build` in `:app` mitgeändert werden.
    #[test]
    fn message_layout_is_tag_length_challenge_digest() {
        let challenge = vec![0xAAu8; 32];
        let message = failsafe_response_message(&challenge, "korrekthorsebatterie").expect("build");

        assert_eq!(&message[..DOMAIN_TAG.len()], DOMAIN_TAG);
        assert_eq!(
            &message[DOMAIN_TAG.len()..DOMAIN_TAG.len() + 2],
            &[0x00, 0x20]
        );
        assert_eq!(
            &message[DOMAIN_TAG.len() + 2..DOMAIN_TAG.len() + 34],
            &challenge[..]
        );
        assert_eq!(message.len(), DOMAIN_TAG.len() + 2 + 32 + 32);
    }

    /// Muss byteweise identisch zum gleichnamigen Vektor in
    /// `FailsafeResponseMessageTest.sharedVectorHex` (`:app`) bleiben. Fest eingetragen, nicht
    /// nachgerechnet: ein Test, der die Erwartung mit derselben Formel bildet, die er prüft,
    /// würde eine gemeinsame Formeländerung auf beiden Seiten stillschweigend mitgehen.
    #[test]
    fn matches_shared_test_vector() {
        let challenge = vec![0xAAu8; 32];
        let message = failsafe_response_message(&challenge, "korrekthorsebatterie").expect("build");
        assert_eq!(
            hex::encode(&message),
            concat!(
                "77617264656e3a6661696c736166653a76320020",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "564543640357e7fd1f0fc0b9c2ca3246e986e3de0f502c040c0663b61dfcb27f",
            )
        );
    }

    #[test]
    fn different_credential_yields_different_message() {
        let challenge = vec![0x01u8; 32];
        let a = failsafe_response_message(&challenge, "passwort-eins-lang").expect("build");
        let b = failsafe_response_message(&challenge, "passwort-zwei-lang").expect("build");
        assert_ne!(a, b);
    }
}
