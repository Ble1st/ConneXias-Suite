package de.ble1st.warden.domain.config

/**
 * "Konfigurations-Export/-Import" (2026-09-03, Ideenliste Punkt 6) — ein reiner Datenschnappschuss
 * von Wardens **lokaler App-Konfiguration**, kein Backup im weiteren Sinn. Bewusst
 * ausgeschlossen, dieselbe Grenze wie sie das Ideenliste-Gespräch schon vorgab: der PIN-Blob, jedes
 * Kryptomaterial (KEK/DEK), das Audit-Log/Security-Event-Log und jeder Sentinel-seitige Zustand —
 * ein wiederhergestelltes Gerät bekommt dieselbe *Härtungskonfiguration* zurück, nicht dieselbe
 * *Identität* (kein PIN-Klon, kein geteiltes Schlüsselmaterial über einen Export hinweg).
 *
 * Jedes Feld hat einen sicheren Default (leer/aus/`null`) — ein [WardenConfigCodec.decode] auf
 * einer *älteren* Export-Datei (aus einer Version vor einem neuen Feld) liefert für das neue Feld
 * einfach den Default, statt zu scheitern. Neue Felder ergänzen dieses Objekt und
 * [de.ble1st.warden.config.WardenConfigExporter]/[de.ble1st.warden.config.WardenConfigImporter];
 * ältere Exporte bleiben dadurch importierbar.
 */
data class WardenConfigSnapshot(
    /** Aktueller Ist-Zustand der toggle-baren Härtungsschalter aus `SafeguardCatalog
     * .reversible()` (`id -> aktiv`) — bewusst der *Ist*-Zustand, nicht ein separat geführter
     * Soll-Wert: nach erfolgreicher Anwendung sollen beide ohnehin übereinstimmen, und der
     * Ist-Zustand ist das, was der Nutzer beim Export tatsächlich vor sich sieht. */
    val safeguardActiveState: Map<String, Boolean> = emptyMap(),
    /** [de.ble1st.warden.domain.profile.WardenProfile.name] des zuletzt angewendeten Profils, oder
     * `null`. Import wendet dieses Profil erneut an — s. Importer-Klassendoc für die Reihenfolge
     * gegenüber [safeguardActiveState]. */
    val effectiveProfile: String? = null,
    val clipboardGuardEnabled: Boolean = false,
    val clipboardGuardThresholdMillis: Long = 0L,
    val clipboardCrossAppMonitoringEnabled: Boolean = false,
    /** [de.ble1st.warden.domain.sim.SimChangeReaction.name], oder `null` = aus. */
    val simChangeReaction: String? = null,
    /** [de.ble1st.warden.domain.cellsecurity.CellSecurityReaction.name], oder `null` = aus. */
    val cellSecurityReaction: String? = null,
    /** [de.ble1st.warden.domain.wifitrust.WifiTrustReaction.name], oder `null` = aus. */
    val wifiTrustReaction: String? = null,
    val trustedWifiSsids: Set<String> = emptySet(),
    val antiTheftMotionAlarmEnabled: Boolean = false,
    val antiTheftChargerAlarmEnabled: Boolean = false,
    val lockScreenText: String? = null,
    val organizationName: String? = null,
    val supportMessage: String? = null,
    val autoRebootThresholdHours: Int? = null,
    val failedAttemptsRebootThreshold: Int? = null,
    /** [de.ble1st.warden.domain.profile.WardenProfile.name] für Nacht-/Tag-Automatik, oder `null`
     * = "nicht umschalten" für diese Tageshälfte. */
    val autoProfileNightProfile: String? = null,
    val autoProfileDayProfile: String? = null,
    val autoProfileNightStartMinuteOfDay: Int? = null,
    val autoProfileNightEndMinuteOfDay: Int? = null,
    val autoProfileEscalateOnCriticalThreat: Boolean = false,
)
