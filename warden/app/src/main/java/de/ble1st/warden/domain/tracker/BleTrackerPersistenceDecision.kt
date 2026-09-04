package de.ble1st.warden.domain.tracker

/**
 * Reine Schwellenwert-Logik für [de.ble1st.warden.tracker.BleTrackerController] (2026-09-03) — ist
 * ein über mehrere periodische BLE-Scans hinweg immer wieder gesehenes, nicht mit dem Gerät
 * gekoppeltes BLE-Gerät auffällig genug, um zu melden?
 *
 * **Grundsätzliche Grenze dieses Ansatzes, nicht nur dieser Funktion:** ein echtes AirTag rotiert
 * seine beworbene Bluetooth-Adresse und die dazugehörigen Identifikator-Bytes bewusst regelmäßig
 * (Apples eigener Datenschutzmechanismus, damit ein Tag nicht über einen langen Zeitraum passiv
 * verfolgt werden kann — exakt das, was diese Funktion versucht). Eine adress-basierte Verfolgung
 * über Stunden/Tage hinweg funktioniert deshalb zuverlässig nur bei nicht-rotierenden Billig-
 * BLE-Tags, nicht bei einem tatsächlichen AirTag. Innerhalb eines einzelnen Rotationsfensters
 * (praxisnah eher Minuten als Stunden) bleibt die Adresse stabil genug, dass mehrere periodische
 * Scans in kurzem Abstand ein Gerät trotzdem als "bleibt in der Nähe" erkennen können — deshalb
 * bewusst niedrige Standardschwellen ([de.ble1st.warden.tracker.BleTrackerController]) statt einer
 * auf Tage ausgelegten Beobachtungsspanne.
 */
object BleTrackerPersistenceDecision {
    fun isSuspicious(
        sightingCount: Int,
        firstSeenMillis: Long,
        lastSeenMillis: Long,
        minSightings: Int,
        minSpanMillis: Long,
    ): Boolean = sightingCount >= minSightings && (lastSeenMillis - firstSeenMillis) >= minSpanMillis
}
