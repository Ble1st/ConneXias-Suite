package de.ble1st.warden.domain.securitylog

import de.ble1st.warden.domain.appmanagement.ThreatSeverity

/**
 * "System-Ereignisprotokoll" (2026-08-28, aus der Lückenanalyse) — die framework-freie Hälfte der
 * Auswertung von `SecurityLog`/`NetworkEvent`.
 *
 * **Warum das überhaupt gebraucht wird:** `SecurityLoggingSafeguard`/`NetworkLoggingSafeguard`
 * schalten seit Langem echtes DPM-Logging scharf, und `WardenDeviceAdminReceiver` rief die Batches
 * auch korrekt ab — schrieb dann aber nur die *Anzahl* der Ereignisse ins Audit-Log und warf den
 * Inhalt weg. Genau das, was nach einem Vorfall zählt (adb-Kommandos, App-Installationen,
 * fehlgeschlagene Entsperrversuche, neu installierte Zertifikate), war damit nirgends einsehbar.
 *
 * Die Zuordnung Ereignistyp → [ThreatSeverity] folgt derselben Linie wie beim Verdachtsscanner
 * (s. [ThreatSeverity]-Klassendoc): beobachtete *Vorgänge* ohne Angriffscharakter sind
 * [ThreatSeverity.INFO], sicherheitsrelevante Eingriffe [ThreatSeverity.WARNING], und Vorgänge,
 * die entweder auf einen aktiven Eingriff von außen oder auf eine verletzte Integritätsannahme
 * hindeuten, [ThreatSeverity.CRITICAL].
 */
enum class SecurityLogEventType(val label: String, val severity: ThreatSeverity) {
    // --- Kritisch: aktiver Eingriff oder gebrochene Annahme -------------------------------------
    /** Ein Kommando über die adb-Shell — auf einem Gerät, das niemand gerade entwickelt, ist das
     * der klassische Angriffsweg mit physischem Zugriff. */
    ADB_SHELL_KOMMANDO("adb-Shell-Kommando", ThreatSeverity.CRITICAL),
    ADB_SHELL_INTERAKTIV("Interaktive adb-Shell geöffnet", ThreatSeverity.CRITICAL),
    ADB_DATEI_EMPFANGEN("Datei per adb auf das Gerät geschoben", ThreatSeverity.CRITICAL),
    ADB_DATEI_GESENDET("Datei per adb vom Gerät gezogen", ThreatSeverity.CRITICAL),

    /** Eine neue Zertifizierungsstelle im System-Truststore hebelt TLS gegenüber allen Apps aus,
     * die kein eigenes Pinning machen — der Standardaufbau für Mitlesen im Netz. */
    ZERTIFIKATSSTELLE_INSTALLIERT("Zertifizierungsstelle installiert", ThreatSeverity.CRITICAL),
    SCHLUESSEL_INTEGRITAET_VERLETZT("Keystore-Integrität verletzt", ThreatSeverity.CRITICAL),
    WIPE_FEHLGESCHLAGEN("Werksreset fehlgeschlagen", ThreatSeverity.CRITICAL),
    ZERTIFIKATSPRUEFUNG_FEHLGESCHLAGEN("Zertifikatsprüfung fehlgeschlagen", ThreatSeverity.CRITICAL),

    // --- Warnung: sicherheitsrelevanter Eingriff ------------------------------------------------
    ENTSPERRVERSUCH("Entsperrversuch am Sperrbildschirm", ThreatSeverity.WARNING),
    PAKET_INSTALLIERT("App installiert", ThreatSeverity.WARNING),
    PAKET_AKTUALISIERT("App aktualisiert", ThreatSeverity.WARNING),
    PAKET_DEINSTALLIERT("App deinstalliert", ThreatSeverity.WARNING),
    ZERTIFIKATSSTELLE_ENTFERNT("Zertifizierungsstelle entfernt", ThreatSeverity.WARNING),
    NUTZER_RESTRIKTION_ENTFERNT("Nutzer-Restriktion aufgehoben", ThreatSeverity.WARNING),
    PASSWORT_GEAENDERT("Sperrbildschirm-Code geändert", ThreatSeverity.WARNING),
    MEDIUM_EINGEHAENGT("Externes Medium eingehängt", ThreatSeverity.WARNING),
    FERNSPERRE("Gerät ferngesperrt", ThreatSeverity.WARNING),
    LOGGING_GESTOPPT("Sicherheitsprotokollierung gestoppt", ThreatSeverity.WARNING),

    // --- Info: normale Betriebsvorgänge ---------------------------------------------------------
    NUTZER_RESTRIKTION_HINZUGEFUEGT("Nutzer-Restriktion gesetzt", ThreatSeverity.INFO),
    PROZESS_GESTARTET("App-Prozess gestartet", ThreatSeverity.INFO),
    SPERRE_AUFGEHOBEN("Sperrbildschirm entsperrt", ThreatSeverity.INFO),
    SPERRE_AKTIV("Sperrbildschirm aktiviert", ThreatSeverity.INFO),
    SYSTEM_START("System gestartet", ThreatSeverity.INFO),
    SYSTEM_HERUNTERGEFAHREN("System heruntergefahren", ThreatSeverity.INFO),
    LOGGING_GESTARTET("Sicherheitsprotokollierung gestartet", ThreatSeverity.INFO),
    MEDIUM_AUSGEHAENGT("Externes Medium ausgehängt", ThreatSeverity.INFO),
    KRYPTO_SELBSTTEST("Krypto-Selbsttest abgeschlossen", ThreatSeverity.INFO),
    RICHTLINIE_GESETZT("Geräterichtlinie gesetzt", ThreatSeverity.INFO),

    /** Netzwerk-Log (eigener DPM-Kanal, nicht `SecurityLog`). Bewusst [ThreatSeverity.INFO]:
     * Auflösungen und Verbindungen fallen im Normalbetrieb massenhaft an — hier zählt die
     * Nachvollziehbarkeit im Nachhinein, nicht der Alarm. */
    DNS_AUFLOESUNG("DNS-Auflösung", ThreatSeverity.INFO),
    NETZWERKVERBINDUNG("Netzwerkverbindung", ThreatSeverity.INFO),

    /** Ereignistyp, den diese Zuordnung (noch) nicht kennt — bewusst nicht verworfen: ein
     * unbekannter Typ soll sichtbar bleiben, nicht stillschweigend fehlen. */
    SONSTIGES("Sonstiges Systemereignis", ThreatSeverity.INFO),
    ;

    val isNetworkEvent: Boolean
        get() = this == DNS_AUFLOESUNG || this == NETZWERKVERBINDUNG
}

/**
 * Ein einzelnes, bereits ausgewertetes Ereignis. [detail] ist die vom System mitgelieferte
 * Nutzlast in lesbarer Form (Paketname, Kommandozeile, Hostname …), bewusst als bereits
 * formatierter String statt als roher `Object`-Baum — die Android-spezifische Umwandlung passiert
 * in `de.ble1st.warden.logging.SecurityEventParser`, hier liegt nur noch, was angezeigt wird.
 */
data class SecurityLogRecord(
    val timestampMillis: Long,
    val type: SecurityLogEventType,
    val detail: String,
) {
    val severity: ThreatSeverity get() = type.severity
}
