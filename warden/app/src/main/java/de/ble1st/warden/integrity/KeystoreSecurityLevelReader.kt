package de.ble1st.warden.integrity

import android.content.Context
import de.ble1st.warden.crypto.KeystoreKek
import de.ble1st.warden.domain.encryption.KeystoreSecurityLevel

/**
 * Feature 5 "Storage Encryption Verification" (`warden/docs/phase-0-design-features-2-7.md`,
 * nachgeholt 2026-09-04), ergänzend zu [StorageEncryptionStatusReader] — Geräteverschlüsselung
 * und KeyStore-Hardwarebindung sind zwei unabhängige Signale, s.
 * [de.ble1st.warden.domain.encryption.EncryptionRecommendationDecision]-Klassendoc.
 *
 * Eine eigene, rein diagnostische [KeystoreKek]-Instanz unter dem Zweck `"diagnostic"` — bewusst
 * nicht der Registry-/Log-KEK wiederverwendet, um diesen Lesezugriff nicht an interne Alias-Zwecke
 * anderer Module zu koppeln. Der zugrundeliegende Schlüssel liegt in genau derselben
 * StrongBox/TEE-Klasse wie jeder andere Warden-Zweck-KEK (identischer `KeyGenParameterSpec`), sein
 * `securityLevel()` ist damit ein repräsentatives Abbild dessen, was auch Wardens eigene PIN-/
 * Registry-/Log-Verschlüsselung tatsächlich schützt.
 */
class KeystoreSecurityLevelReader(context: Context) {

    private val diagnosticKek = KeystoreKek.forPurpose(context, "diagnostic")

    fun read(): KeystoreSecurityLevel = diagnosticKek.securityLevel()
}
