package de.ble1st.warden.crypto

import uniffi.connexias_engine.Envelope
import uniffi.connexias_engine.GeneratedDek
import uniffi.connexias_engine.KeyWrapper
import uniffi.connexias_engine.PasswordHash
import uniffi.connexias_engine.deriveKey as engineDeriveKey
import uniffi.connexias_engine.generateAndWrapDek as engineGenerateAndWrapDek
import uniffi.connexias_engine.generateChallenge as engineGenerateChallenge
import uniffi.connexias_engine.generateDek as engineGenerateDek
import uniffi.connexias_engine.hashPassword as engineHashPassword
import uniffi.connexias_engine.open as engineOpen
import uniffi.connexias_engine.seal as engineSeal
import uniffi.connexias_engine.unwrapDek as engineUnwrapDek
import uniffi.connexias_engine.verifyPassword as engineVerifyPassword
import uniffi.connexias_engine.verifySignature as engineVerifySignature

/**
 * Dünne, idiomatische Kotlin-Fassade über die generierten UniFFI-Bindings
 * (`uniffi.connexias_engine.*`) — der Rest der App ruft `Engine.seal(...)` statt direkt in das
 * generierte Paket zu greifen (Konzept Abschnitt 10a: `seal(context, plaintext)`/
 * `open(context, envelope)` mit `context` als AAD).
 *
 * Reine Delegation, keine eigene Logik — die Krypto lebt ausschließlich in Rust
 * (`rust/engine/src/`). Wirft [uniffi.connexias_engine.SealException]/`OpenException`/
 * `KdfException`/`PasswordException`/`WrapException` unverändert durch.
 *
 * Alle Delegate-Aufrufe importieren die generierten Funktionen unter Alias (`as engineXxx`) —
 * ohne Alias würde z. B. `deriveKey()` innerhalb von `Engine.deriveKey()` sich selbst statt die
 * UniFFI-Funktion aufrufen (gleicher Name, Member-Funktion gewinnt vor Top-Level-Import).
 */
object Engine {

    fun seal(key: ByteArray, context: ByteArray, plaintext: ByteArray): Envelope =
        engineSeal(key, context, plaintext)

    fun open(key: ByteArray, context: ByteArray, envelope: Envelope): ByteArray =
        engineOpen(key, context, envelope)

    fun deriveKey(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray, length: UInt): ByteArray =
        engineDeriveKey(inputKeyMaterial, salt, info, length)

    fun hashPassword(password: ByteArray): PasswordHash = engineHashPassword(password)

    fun verifyPassword(password: ByteArray, hash: PasswordHash): Boolean =
        engineVerifyPassword(password, hash)

    /** Zufälliger DEK (Data-Encryption-Key), noch ungewrappt — siehe [generateAndWrapDek] für
     * den üblichen Fall (Erzeugung + sofortiges Keystore-Wrap in einem Aufruf). */
    fun generateDek(length: UInt): ByteArray = engineGenerateDek(length)

    /**
     * Erzeugt einen frischen DEK und wrappt ihn sofort über `wrapper` (Meilenstein B.3,
     * Konzept 10a) mit dem TEE-/StrongBox-gebundenen Keystore-KEK — siehe
     * [de.ble1st.warden.crypto.KeystoreKek]. [GeneratedDek.dek] ist sofort für
     * [seal]/[open] verwendbar; [GeneratedDek.wrapped] ist die zu persistierende Form.
     */
    fun generateAndWrapDek(wrapper: KeyWrapper, length: UInt): GeneratedDek =
        engineGenerateAndWrapDek(wrapper, length)

    /** Entwrappt einen zuvor mit [generateAndWrapDek] erzeugten und gespeicherten DEK. */
    fun unwrapDek(wrapper: KeyWrapper, wrapped: Envelope): ByteArray =
        engineUnwrapDek(wrapper, wrapped)

    /**
     * Prüft `signature` über `message` gegen `publicKey` (Ed25519) — die einzige der
     * Signing-Funktionen, die tatsächlich auf dem Gerät läuft. Wirft
     * [uniffi.connexias_engine.SigningException] bei strukturell ungültigen Eingaben (falsche
     * Schlüssel-/Signaturlänge); liefert `false` (nicht Exception) für eine wohlgeformte, aber
     * falsche Signatur. Aufrufer siehe [OfflineFailsafeVerifier] — dort werden beide Fälle
     * einheitlich als "nicht verifiziert" behandelt (Fail-Safe, Invariante 6).
     *
     * **Keygen/Sign sind bewusst nicht Teil dieser Kotlin-Fassade** (2026-09-04): die
     * entsprechenden Rust-Funktionen sind nicht mehr `#[uniffi::export]` (s. `signing.rs`), da sie
     * laut Konzept nie auf dem Gerät laufen dürfen — nur das Offline-Tool auf der Air-Gap-Maschine
     * nutzt sie als reine Rust-Funktionen. Eine Kotlin-Verfügbarkeit wäre ein versehentlicher
     * Missbrauchspfad, der nun strukturell ausgeschlossen ist.
     */
    fun verifySignature(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        engineVerifySignature(publicKey, message, signature)

    /** Zufällige Challenge-Bytes für den Offline-Failsafe (Konzept Abschnitt 9) — auf dem Gerät
     * erzeugt und angezeigt, s. [OfflineFailsafeVerifier]. */
    fun generateChallenge(length: UInt): ByteArray = engineGenerateChallenge(length)
}
