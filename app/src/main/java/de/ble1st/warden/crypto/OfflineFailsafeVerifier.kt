package de.ble1st.warden.crypto

/**
 * Meilenstein D.2 (Konzept Abschnitt 9/19): "Challenge-Response-Verifikation in Rust (Engine),
 * Kotlin-Anbindung." Dünner Wrapper über [Engine.verifySignature] — die einzige Stelle, an der
 * Warden selbst mit dem Offline-Failsafe-Schlüsselpaar in Berührung kommt (nur der öffentliche
 * Teil, s. `rust/engine/src/signing.rs`-Moduldoc: Schlüsselerzeugung/Signieren laufen nie auf
 * dem Gerät).
 *
 * **Fail-Safe (Invariante 6):** [verify] gibt für **jeden** Fehlerfall `false` zurück — sowohl
 * für eine wohlgeformte, aber falsche Signatur (`Engine.verifySignature` liefert bereits `false`)
 * als auch für strukturell ungültige Eingaben (falsche Schlüssel-/Signaturlänge,
 * `SigningException`). Ein Aufrufer darf beide Fälle nie unterscheiden müssen — "nicht
 * verifizierbar" heißt hier immer "Failsafe nicht auslösen", nie ein Sonderfall, der versehentlich
 * doch noch als Erfolg durchgehen könnte. Dasselbe Catch-all-Muster wie
 * [KeystoreKek.unwrap]/`WrapException.Failed`.
 */
class OfflineFailsafeVerifier {
    fun verify(challenge: ByteArray, response: ByteArray, publicKey: ByteArray): Boolean = try {
        Engine.verifySignature(publicKey, challenge, response)
    } catch (e: Exception) {
        false
    }
}
