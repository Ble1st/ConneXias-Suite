package de.ble1st.warden.domain.profile

/**
 * Named hardening sets so the owner does not have to flip ~20 switches by hand.
 * Lockdown-bundle / USB-debug kill stay presence-gated and are **not** part of a profile.
 *
 * Alltag is the general-operation baseline: factory-reset from Settings, safe-boot,
 * lock-screen quick access, and Factory Reset Protection (unlock accounts after an
 * untrusted Recovery wipe). OEM-unlock cannot be set independently via public Device-Owner
 * APIs (`no_oem_unlock` is hidden and immutable); Lockdown turns off debugging features,
 * which also hides the OEM-unlock toggle.
 */
enum class WardenProfile {
    ALLTAG,
    REISE,
    MAXIMAL,
    ;

    val label: String
        get() = when (this) {
            ALLTAG -> "Alltag"
            REISE -> "Reise"
            MAXIMAL -> "Maximal"
        }
}

object WardenProfileSpec {

    fun idsOn(profile: WardenProfile): Set<String> = when (profile) {
        WardenProfile.ALLTAG -> ALLTAG
        WardenProfile.REISE -> REISE
        WardenProfile.MAXIMAL -> MAXIMAL
    }

    fun usbAutoLockEnabled(profile: WardenProfile): Boolean = true

    fun description(profile: WardenProfile): String = when (profile) {
        WardenProfile.ALLTAG ->
            "Alltagsbetrieb: Zurücksetzen in den Einstellungen, Abgesicherter Modus, " +
                "Kontosperre nach Recovery-Wipe und Schnellzugriff auf dem Sperrbildschirm aus. " +
                "Kamera und USB bleiben nutzbar."
        WardenProfile.REISE ->
            "Alltag plus Sensoren, unbekannte Medien, dauerhaftes USB-Daten-aus sowie " +
                "USB-Dateiübertragung und Bluetooth-Dateifreigabe aus."
        WardenProfile.MAXIMAL ->
            "Reise plus biometrie-freie Sperre, nur System-Tastatur/Bedienungshilfen, Audit-Logs, " +
                "2G-Sperre (IMSI-Catcher), NFC und VPN-Einrichtung aus. " +
                "USB-Debug/Lockdown-Bündel bleibt hinter Presence."
    }

    private val ALLTAG = setOf(
        "factory_reset_disabled",
        "safe_boot_disabled",
        "factory_reset_protection",
        "modify_accounts_disabled",
        "lock_screen_privacy",
        "password_complexity_high",
        "auto_lock_timeout",
        "backup_service_disabled",
        "install_unknown_sources_disabled",
        "self_uninstall_protection",
        "force_stop_protection",
        "config_date_time_disabled",
        // 2026-08-28: alltagsneutral, schließt aber den Zweitnutzer-Umgehungsweg.
        "add_user_disabled",
    )

    private val REISE = ALLTAG + setOf(
        "camera_disabled",
        "screen_capture_disabled",
        "microphone_muted",
        "physical_media_mount_disabled",
        "usb_data_signaling_disabled",
        "credential_config_disabled",
        // 2026-08-28: Datenabfluss über USB/Bluetooth zu, Radios und Laden bleiben nutzbar.
        "usb_file_transfer_disabled",
        "bluetooth_sharing_disabled",
    )

    private val MAXIMAL = REISE + setOf(
        "keyguard_hardening",
        "accessibility_lockdown",
        "input_method_lockdown",
        "security_logging_enabled",
        "network_logging_enabled",
        "system_update_policy_automatic",
        // 2026-08-28: spürbare Einschränkungen (kein 2G-Fallback, kein NFC, kein eigenes VPN)
        // — deshalb erst hier, nicht schon in Reise.
        "cellular_2g_disabled",
        "nfc_radio_disabled",
        "config_vpn_disabled",
    )
}
