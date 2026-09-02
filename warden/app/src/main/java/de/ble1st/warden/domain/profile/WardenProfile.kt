package de.ble1st.warden.domain.profile

/**
 * Named hardening sets so the owner does not have to flip ~20 switches by hand.
 * Lockdown-bundle / USB-debug kill stay presence-gated and are **not** part of a profile.
 *
 * Alltag is the general-operation baseline: factory-reset from Settings, safe-boot, and
 * lock-screen quick access. OEM-unlock cannot be set independently via public Device-Owner
 * APIs (`no_oem_unlock` is hidden and immutable); Lockdown turns off debugging features,
 * which also hides the OEM-unlock toggle.
 *
 * **Factory Reset Protection deliberately excluded from every profile (analyse.md, 2026-09-02,
 * Kritisch):** empirically disproven on real hardware
 * ([de.ble1st.warden.registry.FactoryResetProtectionSafeguard]'s classdoc — a Samsung SM-A156B
 * Recovery-Wipe came through without any account challenge despite a correctly written policy). A
 * profile that switched it on automatically would look and feel like a working theft deterrent
 * while empirically not being one — the toggle stays reachable manually in `SafeguardsScreen`
 * (with the same reliability warning shown there), but no `WardenProfile.apply()` call turns it
 * on/off as a side effect any more.
 */
enum class WardenProfile {
    ALLTAG,
    REISE,
    MAXIMAL,
    ;

    /**
     * Härtegrad, größer = strenger (2026-08-28, aus der Code-/Sicherheitsanalyse, Befund Q-1).
     *
     * Diese Ordnung ist **keine Konvention, sondern eine Tatsache über [WardenProfileSpec]**: die
     * drei Mengen sind echt ineinander geschachtelt (`ALLTAG ⊂ REISE ⊂ MAXIMAL`), ein höheres
     * Profil schaltet also ausschließlich zusätzliche Safeguards ein und nie einen ab.
     * `WardenProfileSpecTest.strengthOrderMatchesSubsetOrder` prüft genau das — ohne diesen Test
     * könnte eine spätere Umsortierung der Mengen die Ordnung stillschweigend zur Lüge machen,
     * und [AutoProfileDecision] würde eine Verschärfung für eine Abschwächung halten.
     *
     * Bewusst ein eigenes Feld statt `ordinal`: die Deklarationsreihenfolge einer Enum ist keine
     * Zusage, auf die sich sicherheitsrelevante Logik stützen sollte.
     */
    val strength: Int
        get() = when (this) {
            ALLTAG -> 0
            REISE -> 1
            MAXIMAL -> 2
        }

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
            "Alltagsbetrieb: Zurücksetzen in den Einstellungen, Abgesicherter Modus und " +
                "Schnellzugriff auf dem Sperrbildschirm aus. Kamera und USB bleiben nutzbar. " +
                "Kontosperre nach Recovery-Wipe ist nicht enthalten (Zuverlässigkeit auf echter " +
                "Hardware nicht bestätigt) — bei Bedarf manuell in den Safeguards aktivieren."
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
        // "factory_reset_protection" bewusst NICHT hier (analyse.md 2026-09-02, Kritisch) — s.
        // Klassendoc oben. Bleibt manuell in SafeguardsScreen schaltbar.
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
