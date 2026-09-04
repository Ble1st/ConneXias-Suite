package de.ble1st.warden.domain.encryption

import de.ble1st.warden.domain.appmanagement.ThreatSeverity

/**
 * Feature 5 "Storage Encryption Verification" aus `warden/docs/phase-0-design-features-2-7.md`
 * (2026-08-29, nachgeholt 2026-09-04 nach der `analyse.md`-Bestandsaufnahme "fehlen noch wichtige
 * Funktionen"). Reine Entscheidungslogik, kein Android-Import (CLAUDE.md "Decision/Executor-
 * Trennung") — `SecurityScannerScreen` ruft [evaluate] direkt mit den bereits geladenen
 * [de.ble1st.warden.integrity.DeviceIntegrityStatus]-Feldern auf, kein eigener
 * [de.ble1st.warden.bus.ConcordBus]-Umweg nötig für eine reine Ableitung aus schon vorhandenen
 * Werten.
 *
 * **Abweichungen vom ursprünglichen Plan** (derselbe Realitätsabgleich wie bei den übrigen
 * Features aus diesem Plandokument, s. `CLAUDE.md`/`SecurityScoreDecision`-Klassendoc für den
 * gleichen Stil):
 * - Der Plan sah eine getrennte "FDE vs. FBE"-Prüfung vor. Seit Android 10 ist File-Based
 *   Encryption für jedes Gerät verpflichtend (und bei minSdk 35 sowieso), es gibt nur noch einen
 *   einzigen Geräteverschlüsselungszustand — bereits als
 *   [de.ble1st.warden.integrity.DeviceIntegrityStatus.storageEncrypted] vorhanden. Ein zweiter,
 *   unabhängiger "FDE"-Zustand wäre auf keinem realistischen Zielgerät je vom ersten
 *   unterscheidbar.
 * - "FBE **pro sensibler App**" existiert als Android-API nicht — Verschlüsselung ist ein geräte-/
 *   nutzerweiter Zustand, keine Pro-App-Eigenschaft (Direct-Boot-Awareness beschreibt etwas
 *   anderes, s. CLAUDE.md "Direct Boot / BFU-Bewusstsein").
 * - "External Storage" (SD-Karte/USB-OTG) hat keine öffentliche API, die zwischen adoptiertem
 *   (verschlüsseltem) und portablem (unverschlüsseltem) Speicher unterscheidet — jede Angabe dazu
 *   wäre geraten, nicht gemessen. Bewusst weggelassen statt eines erfundenen Signals, dieselbe
 *   Haltung wie bei der Mobilfunk-/BLE-Tracker-Heuristik: lieber ehrlich fehlend als ein falsches
 *   Sicherheitsgefühl.
 * - "1-Klick-FDE-Aktivierung über DevicePolicyManager" entfällt: Verschlüsselung ist auf jedem
 *   Gerät ab Werk aktiv und lässt sich nicht per Admin-API ein-/ausschalten, ohne das Gerät neu
 *   aufzusetzen — es gibt keine Aktion, die ein Admin hier auslösen könnte. Empfehlungen sind
 *   deshalb rein informativ, ohne Aktions-Button (dieselbe Darstellung wie
 *   [de.ble1st.warden.integrity.DeviceIntegrityStatus] insgesamt).
 * - Neu, nicht im ursprünglichen Plan: [KeystoreSecurityLevel] — die einzige Stelle des
 *   ursprünglichen "KeyStore Hardware-Backed vs Software"-Punkts, die sich über eine echte,
 *   öffentliche Android-API prüfen lässt (`KeyInfo.securityLevel`, API 31+, s.
 *   [de.ble1st.warden.crypto.KeystoreKek.securityLevel]).
 */
object EncryptionRecommendationDecision {

    fun evaluate(
        storageEncrypted: Boolean,
        keystoreSecurityLevel: KeystoreSecurityLevel,
    ): List<EncryptionRecommendation> {
        val recommendations = mutableListOf<EncryptionRecommendation>()
        if (!storageEncrypted) {
            recommendations += EncryptionRecommendation(
                EncryptionRecommendationType.DEVICE_ENCRYPTION_INACTIVE,
                ThreatSeverity.CRITICAL,
            )
        }
        when (keystoreSecurityLevel) {
            KeystoreSecurityLevel.SOFTWARE -> recommendations += EncryptionRecommendation(
                EncryptionRecommendationType.KEYSTORE_SOFTWARE_ONLY,
                ThreatSeverity.WARNING,
            )
            KeystoreSecurityLevel.UNKNOWN -> recommendations += EncryptionRecommendation(
                EncryptionRecommendationType.KEYSTORE_UNKNOWN,
                ThreatSeverity.INFO,
            )
            KeystoreSecurityLevel.HARDWARE_BACKED -> Unit
        }
        return recommendations
    }
}
