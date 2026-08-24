package de.ble1st.warden.integrity

/** Aggregiertes Geräte-Integritäts-Bild für den Sicherheits-Scanner-Bildschirm — kein Binder-/AIDL-
 * Transporttyp mehr nötig (s. [de.ble1st.warden.appmanagement.AppManagementInfo]-Klassendoc für
 * dieselbe Begründung), einfache Datenklasse als [de.ble1st.warden.bus.ConcordBus]-Rückgabewert. */
data class DeviceIntegrityStatus(
    val rootIndicators: Set<RootIndicatorSignal>,
    val adbEnabled: Boolean,
    val developerOptionsEnabled: Boolean,
    /** Eigene Idee (2026-08-22), s. [StorageEncryptionStatusReader]-Klassendoc. */
    val storageEncrypted: Boolean,
)
