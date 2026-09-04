package de.ble1st.warden.domain.config

import org.junit.Assert.assertEquals
import org.junit.Test

class WardenConfigCodecTest {

    @Test
    fun roundTripPreservesAllFields() {
        val snapshot = WardenConfigSnapshot(
            safeguardActiveState = mapOf("camera_disabled" to true, "screen_capture_disabled" to false),
            effectiveProfile = "MAXIMAL",
            clipboardGuardEnabled = true,
            clipboardGuardThresholdMillis = 5000L,
            clipboardCrossAppMonitoringEnabled = true,
            simChangeReaction = "NEUSTART",
            cellSecurityReaction = "NETZWERK_SPERREN",
            wifiTrustReaction = "NUR_MELDEN",
            trustedWifiSsids = setOf("Heimnetz", "Büro-WLAN"),
            antiTheftMotionAlarmEnabled = true,
            antiTheftChargerAlarmEnabled = true,
            lockScreenText = "Zeile 1\nZeile 2 mit \\ Backslash",
            organizationName = "ConneXias",
            supportMessage = "Bei Verlust bitte melden: support@example.test",
            autoRebootThresholdHours = 48,
            failedAttemptsRebootThreshold = 10,
            autoProfileNightProfile = "MAXIMAL",
            autoProfileDayProfile = "ALLTAG",
            autoProfileNightStartMinuteOfDay = 1320,
            autoProfileNightEndMinuteOfDay = 360,
            autoProfileEscalateOnCriticalThreat = true,
        )

        val decoded = WardenConfigCodec.decode(WardenConfigCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun emptySnapshotRoundTrips() {
        val decoded = WardenConfigCodec.decode(WardenConfigCodec.encode(WardenConfigSnapshot()))
        assertEquals(WardenConfigSnapshot(), decoded)
    }

    @Test
    fun decodeIgnoresUnknownLinesAndKeys() {
        val text = "version=1\nthis is garbage\nunknown_future_key=42\nclipboard_guard_enabled=true\n"
        val decoded = WardenConfigCodec.decode(text)
        assertEquals(true, decoded.clipboardGuardEnabled)
    }

    @Test
    fun decodeOfBlankTextYieldsDefaults() {
        assertEquals(WardenConfigSnapshot(), WardenConfigCodec.decode(""))
    }

    @Test
    fun ssidOrderIsPreservedByIndex() {
        val snapshot = WardenConfigSnapshot(trustedWifiSsids = setOf("Z-Netz", "A-Netz", "M-Netz"))
        val decoded = WardenConfigCodec.decode(WardenConfigCodec.encode(snapshot))
        assertEquals(snapshot.trustedWifiSsids, decoded.trustedWifiSsids)
    }
}
