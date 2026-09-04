package de.ble1st.warden.domain.encryption

import de.ble1st.warden.domain.appmanagement.ThreatSeverity

/** Welcher der beiden Rohwerte aus [EncryptionRecommendationDecision.evaluate] den Fund ausgelöst
 * hat — die UI (`SecurityScannerScreen`) übersetzt jeden Typ auf einen eigenen, festen Text statt
 * eine freie Beschreibung mitzuführen (dieselbe Trennung wie bei
 * [de.ble1st.warden.domain.appmanagement.SuspiciousSignal]: die Bedeutung liegt im Enum-Wert, der
 * Text lebt in `strings.xml`). */
enum class EncryptionRecommendationType {
    DEVICE_ENCRYPTION_INACTIVE,
    KEYSTORE_SOFTWARE_ONLY,
    KEYSTORE_UNKNOWN,
}

data class EncryptionRecommendation(
    val type: EncryptionRecommendationType,
    val severity: ThreatSeverity,
)
