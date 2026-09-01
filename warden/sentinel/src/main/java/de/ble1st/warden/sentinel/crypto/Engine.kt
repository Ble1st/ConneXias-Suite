package de.ble1st.warden.sentinel.crypto

import uniffi.connexias_engine.Envelope
import uniffi.connexias_engine.GeneratedDek
import uniffi.connexias_engine.KeyWrapper
import uniffi.connexias_engine.PasswordHash
import uniffi.connexias_engine.generateAndWrapDek as engineGenerateAndWrapDek
import uniffi.connexias_engine.hashPassword as engineHashPassword
import uniffi.connexias_engine.open as engineOpen
import uniffi.connexias_engine.seal as engineSeal
import uniffi.connexias_engine.unwrapDek as engineUnwrapDek
import uniffi.connexias_engine.verifyPassword as engineVerifyPassword

/**
 * Eigene, auf Sentinels tatsächlichen Bedarf reduzierte Fassung von `de.ble1st.warden.crypto
 * .Engine` (Plan-Entscheidung "Crypto-Sharing: Duplizieren" — kein Shared-Modul, eigene,
 * unabhängige Kopie). Nur was [de.ble1st.warden.sentinel.pin.SentinelPinStore]/[EnvelopeFile]
 * tatsächlich brauchen: Envelope-Seal/Open + DEK-Wrap/Unwrap (Storage) und Argon2id-Hash/-Verify
 * (PIN selbst) — kein HKDF/Ed25519/Signing/Challenge, das gehört zu Wardens eigenem
 * Offline-Failsafe, den Sentinel nicht hat.
 */
object Engine {

    fun seal(key: ByteArray, context: ByteArray, plaintext: ByteArray): Envelope =
        engineSeal(key, context, plaintext)

    fun open(key: ByteArray, context: ByteArray, envelope: Envelope): ByteArray =
        engineOpen(key, context, envelope)

    fun hashPassword(password: ByteArray): PasswordHash = engineHashPassword(password)

    fun verifyPassword(password: ByteArray, hash: PasswordHash): Boolean =
        engineVerifyPassword(password, hash)

    fun generateAndWrapDek(wrapper: KeyWrapper, length: UInt): GeneratedDek =
        engineGenerateAndWrapDek(wrapper, length)

    fun unwrapDek(wrapper: KeyWrapper, wrapped: Envelope): ByteArray =
        engineUnwrapDek(wrapper, wrapped)
}
