package de.ble1st.warden.domain.appmanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreatSeverityTest {

    @Test
    fun declarationOnlySignalsAreInfoOrWarning() {
        assertEquals(ThreatSeverity.INFO, ThreatSeverity.of(SuspiciousSignal.OVERLAY_PERMISSION_DECLARED))
        assertEquals(ThreatSeverity.INFO, ThreatSeverity.of(SuspiciousSignal.UNKNOWN_INSTALL_SOURCE))
        assertEquals(ThreatSeverity.WARNING, ThreatSeverity.of(SuspiciousSignal.EXTRA_DEVICE_ADMIN))
        assertEquals(ThreatSeverity.WARNING, ThreatSeverity.of(SuspiciousSignal.ACCESSIBILITY_SERVICE_DECLARED))
        assertEquals(ThreatSeverity.WARNING, ThreatSeverity.of(SuspiciousSignal.NOTIFICATION_LISTENER_DECLARED))
    }

    @Test
    fun activeTransitionSignalsAreCritical() {
        assertEquals(ThreatSeverity.CRITICAL, ThreatSeverity.of(SuspiciousSignal.SIGNING_CERT_CHANGED))
        assertEquals(ThreatSeverity.CRITICAL, ThreatSeverity.of(SuspiciousSignal.DEVICE_ADMIN_NEWLY_ACTIVATED))
        assertEquals(ThreatSeverity.CRITICAL, ThreatSeverity.of(SuspiciousSignal.ACCESSIBILITY_SERVICE_NEWLY_ACTIVATED))
        assertEquals(ThreatSeverity.CRITICAL, ThreatSeverity.of(SuspiciousSignal.VERSION_DOWNGRADED))
    }

    @Test
    fun highestOfEmptySetIsInfo() {
        assertEquals(ThreatSeverity.INFO, ThreatSeverity.highest(emptySet()))
    }

    @Test
    fun highestPicksWorstSignal() {
        val signals = setOf(SuspiciousSignal.OVERLAY_PERMISSION_DECLARED, SuspiciousSignal.DEVICE_ADMIN_NEWLY_ACTIVATED)
        assertEquals(ThreatSeverity.CRITICAL, ThreatSeverity.highest(signals))
    }

    /**
     * Befund S-7 (2026-08-28): [SuspiciousAppScanController.enforce] filtert per
     * `ThreatSeverity.highest(...) < ThreatSeverity.WARNING`, verlässt sich also auf Kotlins
     * `Comparable`-Implementierung über die Deklarationsreihenfolge des Enums. Dieser Test hält
     * fest, dass diese Reihenfolge tatsächlich der Schwere entspricht — eine spätere Umsortierung
     * der drei Werte würde den Filter sonst stillschweigend falsch herum laufen lassen.
     */
    @Test
    fun declarationOrderMatchesSeverity() {
        assertTrue(ThreatSeverity.INFO < ThreatSeverity.WARNING)
        assertTrue(ThreatSeverity.WARNING < ThreatSeverity.CRITICAL)
    }
}
