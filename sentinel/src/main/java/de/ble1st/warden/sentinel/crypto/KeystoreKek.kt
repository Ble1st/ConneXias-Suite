package de.ble1st.warden.sentinel.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.Key
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import uniffi.connexias_engine.Envelope
import uniffi.connexias_engine.KeyWrapper
import uniffi.connexias_engine.WrapException

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_LENGTH_BITS = 128

/**
 * 1:1 Port von `de.ble1st.warden.crypto.KeystoreKek` (s. dessen Klassendoc für die volle
 * Begründung) — eigene, unabhängige Kopie in Sentinels eigenem Prozess/Paket, eigener
 * Alias-Namespace (`sentinel.kek.*` statt `warden.kek.*`, s. [forPurpose]). Kein Keystore-Zugriff
 * ist zwischen den beiden Apps geteilt — Android Keystore-Einträge sind ohnehin per-UID isoliert,
 * die eigene Alias-Namensgebung ist hier nur zur Klarheit, kein Sicherheitsmechanismus für sich.
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
            // Fail-Safe (dieselbe Haltung wie überall in Warden): jeder Fehler wird einheitlich
            // als "nicht entsperrbar" gemeldet, nie als Sonderfall, der in offenen Zustand münden
            // könnte.
            throw WrapException.Failed()
        }
    }

    companion object {
        fun forPurpose(context: Context, purpose: String): KeystoreKek {
            val hasStrongBox = context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_STRONGBOX_KEYSTORE,
            )
            return KeystoreKek(alias = "sentinel.kek.$purpose", hasStrongBox = hasStrongBox)
        }
    }
}
