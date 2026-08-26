package de.ble1st.warden.appmanagement

import de.ble1st.warden.domain.appmanagement.SuspiciousSignal
import de.ble1st.warden.domain.appmanagement.ThreatSeverity

/**
 * Ein Fund des Verdachtsscanners. Wie [AppManagementInfo] eine einfache Datenklasse ohne
 * `Parcelable` (kein Binder-Transport mehr, s. dortiges Klassendoc). [signalsBitmask] kodiert
 * [de.ble1st.warden.domain.appmanagement.SuspiciousSignal] als Bitmaske
 * (`SuspiciousSignal.toBitmask`/`fromBitmask`), weil ein Fund mehrere Signale gleichzeitig tragen
 * kann. [severity] ist [ThreatSeverity.highest] über [signalsBitmask] — hier statt beim
 * Aufrufer berechnet, damit UI und [SuspiciousAppNotifier] dieselbe Ableitung teilen, kein
 * zweiter Berechnungsort ("Threat Alerts & Severity Levels", 2026-08-25).
 */
data class SuspiciousAppFindingInfo(
    val packageName: String,
    val label: String,
    val signalsBitmask: Int,
    val frozen: Boolean,
) {
    val severity: ThreatSeverity get() = ThreatSeverity.highest(SuspiciousSignal.fromBitmask(signalsBitmask))
}
