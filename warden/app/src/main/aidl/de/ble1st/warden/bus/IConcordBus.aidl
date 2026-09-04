// Concord-Bus-Cross-Process-Kanal (2026-08-31, Design-Dok docs/design-barbican-prozess-childvpn.md).
//
// Anders als im ConneXias-Framework-Quellprojekt (dortiges core/ipc/.../IConcordBus.aidl, ein
// cross-APK-Kontrakt mit einem callerPackage-Parameter pro Methode + CallerVerifier gegen fremde
// UIDs) ist dieser Kanal cross-PROZESS, aber innerhalb DERSELBEN App — WardenVpnService/
// BarbicanEngine laufen seit dem Prozess-Split (android:process=":barbican") in einem eigenen
// Linux-Prozess, aber unter derselben UID/Signatur wie Wardens Hauptprozess. Kein
// callerPackage-Parameter, kein CallerVerifier: exported="false" auf ConcordBusService reicht,
// weil das nur fremde UIDs blockiert, nicht denselben-App-anderen-Prozess.
//
// Bewusst nur EINE Methode statt eines Nachbaus des vollen Quellprojekt-Kontrakts (dort 24+
// Methoden für Herald/Sentinel/Barbican als jeweils fremde Aufrufer): der aktuelle Scope braucht
// nur einen Rückkanal für Ereignisse, keinen Lesezugriff (Config liest Barbican direkt per
// EnvelopeFile, s. ConcordBus-Klassendoc) und keine Schalter (die bleiben exklusiv Wardens
// eigener UI vorbehalten, Rolle OWNER). Strukturell weiterhin KEINE destruktive Methode — dieselbe
// Invariante wie im Quellprojekt, hier zusätzlich trivial erfüllt, weil der Kontrakt ohnehin nur
// diese eine, nicht-destruktive Methode kennt.
package de.ble1st.warden.bus;

interface IConcordBus {
    /** Protokollversion — reine Diagnose, kein Kompatibilitäts-Check nötig (anders als im
     * Quellprojekt): Client und Host stammen zwingend aus derselben APK, können also nie
     * auseinanderlaufen. */
    int getBusVersion();

    /** BARBICAN-Rolle: meldet ein Ereignis aus dem :barbican-Prozess zur Protokollierung in
     * Wardens Hash-Chain-Audit-Log — löst selbst keine weitere Aktion aus, verändert keinen
     * Zustand. Direkter HashChainLogStore-Zugriff aus dem :barbican-Prozess wäre ein zweiter,
     * nicht synchronisierter Schreiber auf dieselbe Envelope-Datei (kein File-Locking
     * dokumentiert) — deshalb dieser Umweg über den Hauptprozess, der als einziger Schreiber
     * bleibt. [priority] folgt android.util.Log-Konstanten (Log.INFO/WARN/ERROR). */
    boolean reportBarbicanEvent(int priority, String message);
}
