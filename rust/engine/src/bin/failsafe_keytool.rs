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
//! failsafe-keytool sign <secret_key_hex> <challenge_hex>
//! ```
//!
//! Bewusst ohne Abhängigkeit auf `clap` o. ä. — zwei Unterbefehle, minimale Eingabevalidierung,
//! kein Grund für eine CLI-Parser-Bibliothek.

use connexias_engine::{generate_signing_keypair, sign_message};
use std::env;
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("generate") => {
            run_generate();
            ExitCode::SUCCESS
        }
        Some("sign") => match (args.get(2), args.get(3)) {
            (Some(secret_key_hex), Some(challenge_hex)) => {
                match run_sign(secret_key_hex, challenge_hex) {
                    Ok(()) => ExitCode::SUCCESS,
                    Err(message) => {
                        eprintln!("Fehler: {message}");
                        ExitCode::FAILURE
                    }
                }
            }
            _ => {
                eprintln!("Verwendung: failsafe-keytool sign <secret_key_hex> <challenge_hex>");
                ExitCode::FAILURE
            }
        },
        _ => {
            eprintln!(
                "Verwendung:\n  \
                 failsafe-keytool generate\n  \
                 failsafe-keytool sign <secret_key_hex> <challenge_hex>\n\n\
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

fn run_sign(secret_key_hex: &str, challenge_hex: &str) -> Result<(), String> {
    let secret_key = hex::decode(secret_key_hex)
        .map_err(|e| format!("secret_key ist kein gültiges Hex: {e}"))?;
    let challenge =
        hex::decode(challenge_hex).map_err(|e| format!("challenge ist kein gültiges Hex: {e}"))?;

    let signature = sign_message(secret_key, challenge)
        .map_err(|e| format!("Signieren fehlgeschlagen: {e}"))?;

    println!("signature (hex):   {}", hex::encode(&signature));
    println!();
    println!("Diese Zeichenkette in Wardens Failsafe-Bildschirm als \"Response\" eingeben.");
    Ok(())
}
