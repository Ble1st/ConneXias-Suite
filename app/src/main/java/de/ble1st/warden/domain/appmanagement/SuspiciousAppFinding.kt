package de.ble1st.warden.domain.appmanagement

/** Ein Fund des Verdachtsscanners: ein Fremdpaket und die Menge der Signale, die es ausgelöst
 * haben (nie leer — [SuspiciousAppScanDecision.evaluate] erzeugt nur Einträge mit mindestens
 * einem Signal). */
data class SuspiciousAppFinding(
    val packageName: String,
    val signals: Set<SuspiciousSignal>,
)
