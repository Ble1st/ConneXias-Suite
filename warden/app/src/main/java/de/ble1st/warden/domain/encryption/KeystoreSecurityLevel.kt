package de.ble1st.warden.domain.encryption

/**
 * Ob der AndroidKeyStore-Schlüssel, den Wardens eigene PIN-/Registry-/Log-Verschlüsselung nutzt
 * (s. [de.ble1st.warden.crypto.KeystoreKek]), tatsächlich in dedizierter Sicherheitshardware liegt
 * (StrongBox oder TEE) statt einer reinen Softwareimplementierung — s. [EncryptionRecommendationDecision]
 * für den vollständigen Realitätsabgleich gegen den ursprünglichen Feature-5-Plan.
 *
 * `COMPROMISED` aus dem Plan-Enum (`warden/docs/phase-0-design-features-2-7.md`) fehlt bewusst —
 * dafür gibt es keine über eine öffentliche Android-API erkennbare Bedingung, ein solcher Zustand
 * wäre erfunden statt gemessen.
 */
enum class KeystoreSecurityLevel {
    HARDWARE_BACKED,
    SOFTWARE,
    UNKNOWN,
}
