package de.ble1st.warden.integrity

import android.app.admin.DevicePolicyManager
import android.content.Context

/**
 * Eigene Idee, ergänzend zu den Tier-1-6-Punkten (2026-08-22) — liest den geräteweiten
 * Speicherverschlüsselungsstatus (`DevicePolicyManager.getStorageEncryptionStatus`) als weiteren
 * Geräte-Integritäts-Indikator neben [RootIndicatorScanner]/[DeveloperOptionsStatusReader]. Reiner
 * Lesezugriff, kein Schalter — moderne Android-Geräte sind praktisch immer werkseitig
 * (File-Based Encryption) verschlüsselt; `false` wäre trotzdem ein deutliches Warnsignal
 * (z. B. ein sehr altes Gerät oder eine manuelle Deaktivierung), deshalb hier als eigener
 * Indikator statt stillschweigend als "selbstverständlich" angenommen.
 */
class StorageEncryptionStatusReader(private val context: Context) {

    fun isEncrypted(): Boolean {
        val dpm = checkNotNull(context.getSystemService(DevicePolicyManager::class.java)) {
            "DevicePolicyManager nicht verfügbar"
        }
        return when (dpm.storageEncryptionStatus) {
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE,
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER,
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY,
            -> true
            else -> false
        }
    }
}
