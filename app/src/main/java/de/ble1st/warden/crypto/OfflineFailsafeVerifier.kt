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
    /**
     * [message] ist seit 2026-08-28 **nicht mehr die nackte Challenge**, sondern die von
     * [de.ble1st.warden.domain.failsafe.FailsafeResponseMessage.build] zusammengesetzte Bytefolge
     * — sie bindet die neue Geräte-PIN mit an die Signatur (s. dortiges Klassendoc). Diese Klasse
     * bleibt bewusst dumm: sie prüft eine Signatur über *irgendwelche* Bytes, das Zusammensetzen
     * gehört in die testbare Domain-Schicht.
     */
    fun verify(message: ByteArray, response: ByteArray, publicKey: ByteArray): Boolean = try {
        Engine.verifySignature(publicKey, message, response)
    } catch (e: Exception) {
        false
    }
}
