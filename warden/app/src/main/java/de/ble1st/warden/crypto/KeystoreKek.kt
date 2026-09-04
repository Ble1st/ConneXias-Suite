package de.ble1st.warden.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import de.ble1st.warden.domain.encryption.KeystoreSecurityLevel
import java.security.Key
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import uniffi.connexias_engine.Envelope
import uniffi.connexias_engine.KeyWrapper
import uniffi.connexias_engine.WrapException

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_LENGTH_BITS = 128

/**
 * Meilenstein B.3 (Konzept 10a/12): der KEK entsteht und bleibt vollständig im AndroidKeyStore
 * (TEE, StrongBox falls vorhanden — `hasStrongBox`, Laufzeit-Check per
 * `FEATURE_STRONGBOX_KEYSTORE`, CLAUDE.md/Konzept 0.2/6a). Diese Klasse implementiert den von
 * Rust definierten [KeyWrapper]-Callback-Trait (`rust/engine/src/keystore.rs`) — Rust hat
 * keinen NDK-Pfad zum AndroidKeyStore (Konzept 10a), daher übernimmt Kotlin die eigentliche
 * `javax.crypto.Cipher`-Wrap/Unwrap-Operation; der rohe KEK verlässt den Keystore-Prozessraum
 * nie, nur der (kurzlebige) DEK und dessen Wrap-Ergebnis queren die FFI-Grenze nach Rust.
 *
 * `alias` bindet den KEK an einen Zweck (Konzept 10: "eigene Aliase ... keine App teilt ihren
 * KEK") — pro Envelope-Kontext (Registry, Log, ...) eine eigene Instanz mit eigenem Alias, nie
 * ein geteilter KEK über mehrere Zwecke hinweg.
 */
class KeystoreKek(
    private val alias: String,
    private val hasStrongBox: Boolean,
) : KeyWrapper {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingOrNewKey(): Key = keyStore.getKey(alias, null) ?: generateKey()

    private fun generateKey(): Key {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        return try {
            keyGenerator.init(specFor(strongBoxBacked = hasStrongBox))
            keyGenerator.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            // Laufzeit-Absicherung (CLAUDE.md/Konzept 0.2): hasSystemFeature() ist der
            // dokumentierte Weg, StrongBox zu erkennen, kann im Einzelfall aber danebenliegen —
            // Fallback auf TEE statt Absturz, damit die Rollback-Strategie (Konzept 6a) nicht an
            // einer einzelnen Systemauskunft zerbricht.
            keyGenerator.init(specFor(strongBoxBacked = false))
            keyGenerator.generateKey()
        }
    }

    private fun specFor(strongBoxBacked: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBoxBacked)
            .build()

    /**
     * Feature 5 "Storage Encryption Verification" (`warden/docs/phase-0-design-features-2-7.md`,
     * nachgeholt 2026-09-04, s. [de.ble1st.warden.domain.encryption.EncryptionRecommendationDecision]
     * für den vollständigen Realitätsabgleich): `hasStrongBox`/`FEATURE_STRONGBOX_KEYSTORE` sagt
     * nur, ob StrongBox auf diesem Gerät grundsätzlich *möglich* wäre, nicht ob dieser konkrete
     * Schlüssel tatsächlich so gelandet ist (s. den `StrongBoxUnavailableException`-Fallback
     * oben). `KeyInfo.securityLevel` (API 31+, unter minSdk 35 immer verfügbar) ist der einzige
     * Weg, das am tatsächlichen Schlüssel nachzuprüfen statt nur die Geräte-Fähigkeit zu raten.
     * Für [de.ble1st.warden.integrity.KeystoreSecurityLevelReader].
     */
    fun securityLevel(): KeystoreSecurityLevel = try {
        val factory = SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(existingOrNewKey() as SecretKey, KeyInfo::class.java) as KeyInfo
        when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX,
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
            -> KeystoreSecurityLevel.HARDWARE_BACKED
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeystoreSecurityLevel.SOFTWARE
            else -> KeystoreSecurityLevel.UNKNOWN
        }
    } catch (e: Exception) {
        KeystoreSecurityLevel.UNKNOWN
    }

    override fun wrap(dek: ByteArray): Envelope {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, existingOrNewKey())
            val ciphertext = cipher.doFinal(dek)
            return Envelope(nonce = cipher.iv, ciphertext = ciphertext)
        } catch (e: Exception) {
            throw WrapException.Failed()
        }
    }

    override fun unwrap(wrapped: Envelope): ByteArray {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, wrapped.nonce)
            cipher.init(Cipher.DECRYPT_MODE, existingOrNewKey(), spec)
            return cipher.doFinal(wrapped.ciphertext)
        } catch (e: Exception) {
            // Fail-Safe (Invariante 6): jeder Fehler (falscher Auth-Tag, durch
            // Lockscreen-/Biometrie-Änderung invalidierter Key, fehlender Alias) wird
            // einheitlich als "nicht entsperrbar" gemeldet, nie als Sonderfall, der in offenen
            // Zustand münden könnte.
            throw WrapException.Failed()
        }
    }

    companion object {
        /**
         * Baut eine [KeystoreKek]-Instanz für einen Zweck (`purpose`, z. B. `"registry"`,
         * `"log"`) — die StrongBox-Verfügbarkeit wird pro Aufruf frisch geprüft
         * (`hasSystemFeature`), nicht zwischen App-Starts zwischengespeichert.
         */
        fun forPurpose(context: Context, purpose: String): KeystoreKek {
            val hasStrongBox = context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_STRONGBOX_KEYSTORE,
            )
            return KeystoreKek(alias = "warden.kek.$purpose", hasStrongBox = hasStrongBox)
        }
    }
}
