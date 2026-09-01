package de.ble1st.warden.domain.bus

/**
 * Im Quellprojekt (ConneXias-Framework) unterschied dieses Enum die Cross-Process-Bus-Aufrufer
 * (Herald/Sentinel/Barbican, je eigene APK/UID) von Warden selbst, das keine Rolle brauchte, weil
 * es der Bus-Host war. In diesem Projekt war Concord lange bewusst **In-Process** (`exposed=false`,
 * s. [de.ble1st.warden.bus.ConcordBus]-Klassendoc) — Herald- und Sentinel-Funktionen liefen
 * innerhalb von Warden, der einzige "Aufrufer" war Wardens eigene UI.
 *
 * [OWNER] steht für genau das: Wardens eigene UI, im Hauptprozess. Genau die Erweiterung, die der
 * alte Klassendoc-Kommentar hier vorausgesagt hat, ist mit [BARBICAN] jetzt eingetreten (2026-08-31,
 * Design-Dok `docs/design-barbican-prozess-childvpn.md`): seit dem Prozess-Split
 * (`WardenVpnService`/`BarbicanEngine` laufen in `android:process=":barbican"`) gibt es einen
 * zweiten, echten Cross-Process-Aufrufer — keine fremde APK wie im Quellprojekt (gleiche UID,
 * gleiche Signatur), aber kein plainer In-Process-Methodenaufruf mehr. [BARBICAN] bekommt bewusst
 * nur [de.ble1st.warden.domain.bus.BusCommand.EVENT_REPORT] in
 * [de.ble1st.warden.domain.bus.CapabilityMatrix] — kleinstmögliches Privileg, kein READ/
 * NON_DESTRUCTIVE_SWITCH-Zugriff über den Bus (Config liest der Barbican-Prozess direkt per
 * `EnvelopeFile`).
 */
enum class Role {
    OWNER,
    BARBICAN,
}
