package de.ble1st.warden.domain.bus

/**
 * Im Quellprojekt (ConneXias-Framework) unterschied dieses Enum die Cross-Process-Bus-Aufrufer
 * (Herald/Sentinel/Barbican, je eigene APK/UID) von Warden selbst, das keine Rolle brauchte, weil
 * es der Bus-Host war. In diesem Projekt ist Concord bewusst **In-Process** (`exposed=false`,
 * s. [de.ble1st.warden.bus.ConcordBus]-Klassendoc) — Herald- und Sentinel-Funktionen laufen jetzt
 * innerhalb von Warden, der einzige verbleibende "Aufrufer" ist Wardens eigene UI.
 *
 * [OWNER] steht für genau das: den einzigen heute existierenden Aufrufer. Das Enum bleibt trotzdem
 * bestehen (statt ganz zu entfallen) — sollte Concord später wieder `exposed=true` werden (ein
 * echter, exportierter Service für einen künftigen externen Aufrufer), braucht
 * [de.ble1st.warden.domain.bus.CapabilityMatrix] nur eine neue Rolle, keine strukturelle
 * Änderung der Autorisierungs-Pipeline.
 */
enum class Role {
    OWNER,
}
