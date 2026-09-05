package de.ble1st.warden.integrity

import de.ble1st.warden.domain.advancedprotection.AdvancedProtectionState
import de.ble1st.warden.domain.attestation.DeviceAttestation
import de.ble1st.warden.domain.encryption.KeystoreSecurityLevel

/** Aggregiertes Geräte-Integritäts-Bild für den Sicherheits-Scanner-Bildschirm — kein Binder-/AIDL-
 * Transporttyp mehr nötig (s. [de.ble1st.warden.appmanagement.AppManagementInfo]-Klassendoc für
 * dieselbe Begründung), einfache Datenklasse als [de.ble1st.warden.bus.ConcordBus]-Rückgabewert. */
data class DeviceIntegrityStatus(
    val rootIndicators: Set<RootIndicatorSignal>,
    val adbEnabled: Boolean,
    val developerOptionsEnabled: Boolean,
    /** Eigene Idee (2026-08-22), s. [StorageEncryptionStatusReader]-Klassendoc. */
    val storageEncrypted: Boolean,
    /** Feature 5 "Storage Encryption Verification", s. [KeystoreSecurityLevelReader]-Klassendoc. */
    val keystoreSecurityLevel: KeystoreSecurityLevel,
    /**
     * Key Attestation (2026-09-05, Tier-1 der DPC-Recherche) — der hardware-signierte Nachweis zu
     * Verified Boot, Bootloader-Sperre und Sicherheitspatch-Stand. Steht bewusst **neben**
     * [rootIndicators] statt an dessen Stelle: die Heuristik erkennt Dinge, die Attestation nicht
     * sieht (z. B. ein Magisk auf einem Gerät mit intakter Kette), und umgekehrt. S.
     * [de.ble1st.warden.integrity.KeyAttestationReader].
     */
    val attestation: DeviceAttestation,
    /** Androids eigener „Erweiterter Schutz" (AAPM). Auf Android 15 immer
     * [AdvancedProtectionState.NICHT_VERFUEGBAR] — nicht [AdvancedProtectionState.AUS]. */
    val advancedProtection: AdvancedProtectionState,
)
