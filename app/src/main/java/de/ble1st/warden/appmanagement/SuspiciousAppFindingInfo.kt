package de.ble1st.warden.appmanagement

/**
 * Ein Fund des Verdachtsscanners. Wie [AppManagementInfo] eine einfache Datenklasse ohne
 * `Parcelable` (kein Binder-Transport mehr, s. dortiges Klassendoc). [signalsBitmask] kodiert
 * [de.ble1st.warden.domain.appmanagement.SuspiciousSignal] als Bitmaske
 * (`SuspiciousSignal.toBitmask`/`fromBitmask`), weil ein Fund mehrere Signale gleichzeitig tragen
 * kann.
 */
data class SuspiciousAppFindingInfo(
    val packageName: String,
    val label: String,
    val signalsBitmask: Int,
    val frozen: Boolean,
)
