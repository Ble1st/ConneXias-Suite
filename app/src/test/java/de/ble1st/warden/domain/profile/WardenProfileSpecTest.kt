package de.ble1st.warden.domain.profile

import org.junit.Assert.assertTrue
import org.junit.Test

class WardenProfileSpecTest {

    @Test
    fun alltagBlocksResetAndLockScreenQuickAccess() {
        val ids = WardenProfileSpec.idsOn(WardenProfile.ALLTAG)
        assertTrue(ids.contains("factory_reset_disabled"))
        assertTrue(ids.contains("safe_boot_disabled"))
        assertTrue(ids.contains("factory_reset_protection"))
        assertTrue(ids.contains("modify_accounts_disabled"))
        assertTrue(ids.contains("lock_screen_privacy"))
    }

    @Test
    fun alltagKeepsCameraAndUsbUsable() {
        val ids = WardenProfileSpec.idsOn(WardenProfile.ALLTAG)
        assertTrue("camera_disabled" !in ids)
        assertTrue("usb_data_signaling_disabled" !in ids)
        assertTrue("debugging_features_disabled" !in ids)
    }

    @Test
    fun reiseExtendsAlltag() {
        assertTrue(
            WardenProfileSpec.idsOn(WardenProfile.REISE)
                .containsAll(WardenProfileSpec.idsOn(WardenProfile.ALLTAG)),
        )
        assertTrue(WardenProfileSpec.idsOn(WardenProfile.REISE).contains("usb_data_signaling_disabled"))
        assertTrue(WardenProfileSpec.idsOn(WardenProfile.REISE).contains("camera_disabled"))
    }

    @Test
    fun maximalExtendsReiseWithoutDebugKill() {
        assertTrue(
            WardenProfileSpec.idsOn(WardenProfile.MAXIMAL)
                .containsAll(WardenProfileSpec.idsOn(WardenProfile.REISE)),
        )
        assertTrue("debugging_features_disabled" !in WardenProfileSpec.idsOn(WardenProfile.MAXIMAL))
        assertTrue(WardenProfileSpec.usbAutoLockEnabled(WardenProfile.ALLTAG))
    }

    @Test
    fun profileIdsAreKnownReversibleCatalogMembers() {
        val catalog = setOf(
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
            "camera_disabled",
            "screen_capture_disabled",
            "microphone_muted",
            "physical_media_mount_disabled",
            "usb_data_signaling_disabled",
            "credential_config_disabled",
            "keyguard_hardening",
            "accessibility_lockdown",
            "input_method_lockdown",
            "security_logging_enabled",
            "network_logging_enabled",
            "system_update_policy_automatic",
        )
        for (profile in WardenProfile.entries) {
            val ids = WardenProfileSpec.idsOn(profile)
            assertTrue("$profile contains unknown id: ${ids - catalog}", catalog.containsAll(ids))
        }
        assertTrue(catalog.containsAll(WardenProfileSpec.idsOn(WardenProfile.MAXIMAL)))
        assertTrue(WardenProfileSpec.idsOn(WardenProfile.MAXIMAL).containsAll(catalog))
    }
}
