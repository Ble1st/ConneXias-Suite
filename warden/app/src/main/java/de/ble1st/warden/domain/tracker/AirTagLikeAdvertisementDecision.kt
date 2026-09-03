package de.ble1st.warden.domain.tracker

/**
 * Reine Byte-Erkennung für Apples "Offline Finding"-Werbepaket (2026-09-03, Ideenliste
 * "BLE-Tracker-Wächter, auch AirTags") — das öffentlich reverse-engineerte Format, das AirTags,
 * AirPods-Hüllen im getrennten Zustand und andere "Find My"-fähige Zubehörteile über Bluetooth Low
 * Energy aussenden (Grundlage u. a. der Open-Source-Projekte OpenHaystack/AirGuard).
 *
 * **Erkennt "ein Find-My-fähiges Apple-Zubehörteil sendet hier", nicht "das ist ein AirTag".** Das
 * öffentlich bekannte Werbeformat unterscheidet Gerätetypen (AirTag vs. AirPods-Hülle vs. ein
 * Fremdhersteller-Tag mit Find-My-Unterstützung) nicht über den frei auslesbaren Teil des Pakets —
 * eine genauere Zuordnung würde Apples proprietäre Krypto-Details voraussetzen, die hier bewusst
 * nicht nachgebildet werden. Diese Unschärfe ist eine dokumentierte, akzeptierte Grenze, keine
 * versehentliche — dieselbe "Heuristik, kein Beweis"-Haltung wie
 * [de.ble1st.warden.domain.cellsecurity.CellSecurityDecision].
 *
 * Prüfung: Apple als Hersteller-ID (`0x004C`, von Bluetooth SIG vergeben), erstes Payload-Byte
 * `0x12` (Typ "Offline Finding"), zweites Byte `0x19` (Länge 25 — die feste Länge dieses
 * Pakettyps). **Nicht geprüft:** das Statusbyte (Bit-Bedeutung "bei Besitzer"/"getrennt") — dessen
 * genaue Semantik ist in öffentlichen Quellen uneinheitlich dokumentiert; ein falsch interpretiertes
 * Bit würde hier mehr Schaden (falsche Sicherheit) als Nutzen stiften, deshalb bewusst weggelassen.
 */
object AirTagLikeAdvertisementDecision {
    private const val APPLE_MANUFACTURER_ID = 0x004C
    private const val OFFLINE_FINDING_TYPE_BYTE: Byte = 0x12
    private const val OFFLINE_FINDING_LENGTH_BYTE: Byte = 0x19

    fun isFindMyShaped(manufacturerId: Int, payload: ByteArray?): Boolean {
        if (manufacturerId != APPLE_MANUFACTURER_ID) return false
        if (payload == null || payload.size < 2) return false
        return payload[0] == OFFLINE_FINDING_TYPE_BYTE && payload[1] == OFFLINE_FINDING_LENGTH_BYTE
    }
}
