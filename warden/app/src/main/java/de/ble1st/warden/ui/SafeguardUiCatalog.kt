package de.ble1st.warden.ui

import de.ble1st.warden.domain.profile.WardenProfile
import de.ble1st.warden.domain.profile.WardenProfileSpec
import de.ble1st.warden.registry.AccessibilityLockdownSafeguard
import de.ble1st.warden.registry.AutoLockTimeoutSafeguard
import de.ble1st.warden.registry.BackupServiceLockdownSafeguard
import de.ble1st.warden.registry.CameraSafeguard
import de.ble1st.warden.registry.FactoryResetProtectionSafeguard
import de.ble1st.warden.registry.ForceStopProtectionSafeguard
import de.ble1st.warden.registry.InputMethodLockdownSafeguard
import de.ble1st.warden.registry.KeyguardHardeningSafeguard
import de.ble1st.warden.registry.LockScreenPrivacySafeguard
import de.ble1st.warden.registry.MtePolicySafeguard
import de.ble1st.warden.registry.NetworkLoggingSafeguard
import de.ble1st.warden.registry.PasswordComplexitySafeguard
import de.ble1st.warden.registry.ScreenCaptureSafeguard
import de.ble1st.warden.registry.SecurityLoggingSafeguard
import de.ble1st.warden.registry.SelfUninstallProtectionSafeguard
import de.ble1st.warden.registry.SentinelUninstallProtectionSafeguard
import de.ble1st.warden.registry.SystemUpdatePolicySafeguard
import de.ble1st.warden.registry.UsbDataSignalingSafeguard
import de.ble1st.warden.registry.UserRestrictionSafeguard

/**
 * Beschreibungsdaten für [SafeguardsScreen] (Vorschlag U-1 aus dem Prüfbericht, 2026-08-29).
 *
 * **Warum es diese Datei gibt:** [SafeguardsScreen] nahm vorher **33 einzeln durchgereichte**
 * `SafeguardToggleState`-Parameter entgegen, und jede Zeile stand zusätzlich handverdrahtet im
 * Rumpf. Ein neuer Safeguard bedeutete damit drei Stellen: Registry-Katalog
 * ([de.ble1st.warden.registry.SafeguardCatalog]), Screen-Signatur, Aufrufstelle in
 * [WardenStatusActivity] — drei Gelegenheiten, eine zu vergessen. Genau das ist historisch
 * passiert: `SentinelUninstallProtectionSafeguard` war ab 2026-08-26 im Registry-Katalog, bekam
 * seine UI-Zeile aber erst am 2026-08-27 nachgereicht (s. dessen Klassendoc).
 *
 * Jetzt trägt der Katalogeintrag seine eigenen Metadaten (Titel, Beschreibung, Gruppe,
 * Risikoseite), der Screen rendert daraus eine Liste, und ein neuer Safeguard heißt: Registry-
 * Katalog + ein Eintrag hier. Die Screen-Signatur ändert sich nicht mehr mit.
 *
 * **Die Texte sind bewusst unverändert übernommen** — diese Umstellung soll die Struktur ändern,
 * nicht die Aussagen; mehrere davon halten live gewonnene Warnungen fest (FRP-Unzuverlässigkeit,
 * adb-Abriss beim Sperren der Entwickleroptionen).
 */
object SafeguardUiCatalog {

    /**
     * Synthetische ID für "USB-Datenverkehr bei Sperre automatisch deaktivieren" — der einzige
     * Schalter auf diesem Bildschirm, der **kein** Registry-Safeguard ist, sondern nur eine lokale
     * Präferenz ([de.ble1st.warden.usb.UsbAutoLockController] liest sie periodisch aus). Er steht
     * trotzdem hier, weil er für die Nutzerin optisch und thematisch einer von ihnen ist; die
     * Aufrufstelle in [WardenStatusActivity] bildet diese ID auf den Präferenz-Schalter ab statt
     * auf `ConcordBus.applySafeguard`.
     */
    const val USB_AUTO_LOCK_ID = "usb_auto_lock_preference"

    /**
     * Auf welcher Seite eines Schalters das Risiko sitzt — steuert, ob vor dem Umlegen ein
     * Bestätigungsdialog kommt und in welche Richtung.
     */
    enum class RiskSide {
        /** Beide Richtungen unkritisch, ein Tap genügt. */
        NONE,

        /** Das *Ab*schalten ist der riskante Weg (Reset-Schutz: ohne ihn ist das Gerät nach einem
         * Wipe leichter wieder in Betrieb zu nehmen). Einschalten bleibt ungegatet. */
        DISABLING,

        /** Das *Ein*schalten ist der riskante Weg — derzeit nur die Entwickleroptionen-Sperre, die
         * die eigene adb-Verbindung kappt und sich danach nur noch hier zurücknehmen lässt. */
        ENABLING,
    }

    data class Entry(
        val id: String,
        val label: String,
        val supportingText: String? = null,
        val riskSide: RiskSide = RiskSide.NONE,
        /** Nur bei [RiskSide.ENABLING] gesetzt — bei [RiskSide.DISABLING] formuliert der Screen
         * einen einheitlichen Reset-Schutz-Text, der für alle drei Schalter dort passt. */
        val confirmTitle: String? = null,
        val confirmText: String? = null,
    ) {
        /** Für die Suche (Vorschlag U-3): Titel **und** Beschreibung, damit "IMSI" den
         * 2G-Schalter findet, obwohl das Wort nur in der Beschreibung steht. */
        fun matches(query: String): Boolean {
            val needle = query.trim()
            if (needle.isEmpty()) return true
            return label.contains(needle, ignoreCase = true) ||
                supportingText?.contains(needle, ignoreCase = true) == true
        }
    }

    /** [footnote] steht als grauer Fließtext *unter* der Gruppe (bisher inline im Screen-Rumpf). */
    data class Group(
        val id: String,
        val description: String,
        val entries: List<Entry>,
        val footnote: String? = null,
    )

    /**
     * Zu welchen Profilen ein Schalter gehört (Vorschlag U-3) — **abgeleitet**, nicht gepflegt:
     * Quelle ist [WardenProfileSpec] selbst, damit die Anzeige nicht von der tatsächlichen
     * Profildefinition abdriften kann. Gerade seit Befund Q-1 ist das der Unterschied zwischen
     * "mein Schalter bleibt an" und "wird beim nächsten Zeitplanlauf zurückgesetzt".
     */
    fun profilesContaining(id: String): Set<WardenProfile> =
        WardenProfile.entries
            .filter { profile ->
                // USB-Auto-Lock ist kein Registry-Safeguard und steht deshalb in keiner idsOn-Menge,
                // wird von WardenProfileApplier aber trotzdem für jedes Profil gesetzt.
                if (id == USB_AUTO_LOCK_ID) {
                    WardenProfileSpec.usbAutoLockEnabled(profile)
                } else {
                    id in WardenProfileSpec.idsOn(profile)
                }
            }
            .toSet()

    /** Nachschlagen eines Eintrags über seine Registry-`id` (2026-09-05) — für Aufrufstellen
     * außerhalb von [SafeguardsScreen], die denselben Bestätigungstext zeigen wollen, statt ihn ein
     * zweites Mal zu formulieren (s. `SecurityScannerScreen.IntegrityStatusRow`: der dortige
     * "Antippen zum Beheben"-Knopf für `debugging_features_disabled` griff zunächst ungefragt
     * durch — genau der Fall, für den dieser Katalogeintrag seinen Bestätigungstext trägt). */
    fun entryById(id: String): Entry? = groups.flatMap { it.entries }.firstOrNull { it.id == id }

    val groups: List<Group> = listOf(
        Group(
            id = "alltag",
            description = "Alltagsbetrieb — Zurücksetzen und Schnellzugriff.",
            entries = listOf(
                Entry(
                    id = UserRestrictionSafeguard.FACTORY_RESET_DISABLED_ID,
                    label = "Zurücksetzen in den Einstellungen blockieren",
                    supportingText = "Blockiert Werksreset unter Einstellungen. Kein wipeData().",
                    riskSide = RiskSide.DISABLING,
                ),
                Entry(
                    id = UserRestrictionSafeguard.SAFE_BOOT_DISABLED_ID,
                    label = "Abgesicherten Modus blockieren",
                    supportingText = "Verhindert Safe Boot als Recovery-nahen Umgehungsweg.",
                ),
                Entry(
                    id = FactoryResetProtectionSafeguard.ID,
                    label = "Nach Recovery-Wipe Konto verlangen",
                    supportingText = "Recovery-Wipe bleibt möglich, Gerät ist danach ohne dieses Konto nicht " +
                        "neu einzurichten. Wirkt nur bei gesperrtem Bootloader — bei OEM-Unlock " +
                        "wirkungslos. Braucht Google-Play-Dienste / FRP-Agent. ⚠ Auf echter " +
                        "Hardware (Samsung SM-A156B, 2026-08-25) hat ein Recovery-Wipe trotz " +
                        "korrekt gesetzter Policy keine Konto-Abfrage ausgelöst — nicht als " +
                        "verlässlichen Schutz behandeln, s. FactoryResetProtectionSafeguard.",
                    riskSide = RiskSide.DISABLING,
                ),
                Entry(
                    id = UserRestrictionSafeguard.MODIFY_ACCOUNTS_DISABLED_ID,
                    label = "Konten in den Einstellungen nicht ändern lassen",
                    supportingText = "Verhindert, dass jemand das Entsperrkonto vor dem Wipe löscht.",
                    riskSide = RiskSide.DISABLING,
                ),
                Entry(
                    id = UserRestrictionSafeguard.DEBUGGING_FEATURES_DISABLED_ID,
                    label = "Entwickleroptionen/USB-Debugging sperren",
                    supportingText = "Verhindert adb-Zugriff durch Angreifer mit physischem Zugriff " +
                        "(Feature 35). ⚠ Lässt sich laut Android nicht mehr über die " +
                        "Entwickleroptionen zurücknehmen, solange aktiv — auf einem per USB an einen " +
                        "Entwicklungsrechner angeschlossenen Gerät kappt das Einschalten " +
                        "wahrscheinlich sofort die eigene adb-Verbindung.",
                    riskSide = RiskSide.ENABLING,
                    confirmTitle = "Entwickleroptionen/USB-Debugging wirklich sperren?",
                    confirmText = "Kappt vermutlich sofort jede bestehende adb-Verbindung zu diesem " +
                        "Gerät und lässt sich laut Android-Dokumentation nicht mehr über die " +
                        "Entwickleroptionen selbst zurücknehmen — nur noch über diesen Schalter in " +
                        "Warden. Nur auf einem Gerät aktivieren, das nicht mehr per USB entwickelt wird.",
                ),
                Entry(
                    id = LockScreenPrivacySafeguard.ID,
                    label = "Schnellzugriff auf dem Sperrbildschirm sperren",
                    supportingText = "Keine Shortcuts, Kamera, Widgets oder Klartext-Benachrichtigungen.",
                ),
            ),
            footnote = "Bootloader/OEM-Unlock sitzt in den Entwickleroptionen. Die öffentliche " +
                "Device-Owner-API kann das nicht einzeln sperren, ohne USB-Debug mit " +
                "abzuschalten — das macht erst der Lockdown-Modus.",
        ),
        Group(
            id = "sensoren",
            description = "Sensoren.",
            entries = listOf(
                Entry(CameraSafeguard.ID, "Kamera sperren"),
                Entry(ScreenCaptureSafeguard.ID, "Bildschirmaufnahme sperren"),
                Entry(UserRestrictionSafeguard.MICROPHONE_MUTED_ID, "Mikrofon sperren"),
            ),
        ),
        Group(
            id = "anti_tamper",
            description = "Anti-Tamper — schützt Warden selbst vor Deaktivierung.",
            entries = listOf(
                Entry(UserRestrictionSafeguard.CONFIG_DATE_TIME_DISABLED_ID, "Uhrzeit-Manipulation verhindern"),
                Entry(SelfUninstallProtectionSafeguard.ID, "Deinstallation von Warden blockieren"),
                Entry(ForceStopProtectionSafeguard.ID, "Erzwinge-Stopp/Akku-Optimierung blockieren"),
                Entry(
                    UserRestrictionSafeguard.CREDENTIAL_CONFIG_DISABLED_ID,
                    "Zertifikat-/Anmeldedaten-Installation blockieren",
                ),
                Entry(UserRestrictionSafeguard.PHYSICAL_MEDIA_MOUNT_DISABLED_ID, "Externe Datenträger (SD/USB) sperren"),
                Entry(
                    UserRestrictionSafeguard.INSTALL_UNKNOWN_SOURCES_DISABLED_ID,
                    "Installation aus unbekannten Quellen blockieren",
                ),
                Entry(
                    id = UserRestrictionSafeguard.INSTALL_UNKNOWN_SOURCES_GLOBALLY_DISABLED_ID,
                    label = "Unbekannte Quellen geräteweit blockieren (alle Nutzer)",
                    supportingText = "Schärfer als die Zeile darüber: gilt auch für zusätzlich " +
                        "angelegte Nutzer/Gastprofile. Achtung — Warden selbst wird als APK " +
                        "verteilt und lässt sich dann nicht mehr aktualisieren, ohne dies vorher " +
                        "wieder auszuschalten. Bereits installierte Apps bleiben unberührt.",
                ),
                Entry(
                    id = UserRestrictionSafeguard.ADD_USER_DISABLED_ID,
                    label = "Zusätzliche Nutzer/Gastprofil verbieten",
                    supportingText = "Ein neu angelegtes Profil startet ungehärtet — die meisten " +
                        "Schalter hier wirken nutzerbezogen, das Gerät ist aber dasselbe.",
                ),
            ),
        ),
        Group(
            id = "anti_diebstahl",
            description = "Anti-Diebstahl — lokal, kein Standort/Fernzugriff.",
            entries = listOf(
                Entry(KeyguardHardeningSafeguard.ID, "Nur PIN/Passwort (Smart Lock/Biometrie sperren)"),
                Entry(USB_AUTO_LOCK_ID, "USB-Datenverkehr bei Sperre automatisch deaktivieren"),
                Entry(UsbDataSignalingSafeguard.ID, "USB-Datenverkehr dauerhaft deaktivieren"),
                Entry(
                    id = UserRestrictionSafeguard.USB_FILE_TRANSFER_DISABLED_ID,
                    label = "USB-Dateiübertragung (MTP/PTP) sperren",
                    supportingText = "Schwächste der drei USB-Stufen: Laden und Zubehör bleiben " +
                        "nutzbar, nur der Dateizugriff fällt weg.",
                ),
                // Live-Drill-Fund (2026-08-26/2026-08-27): registriert im Katalog seit Sentinels
                // Silent-Install, hatte aber lange keine eigene UI-Zeile — s.
                // SentinelUninstallProtectionSafeguard-Klassendoc. Genau der Fehler, den diese
                // Datei strukturell verhindern soll.
                Entry(SentinelUninstallProtectionSafeguard.ID, "Sentinel-Deinstallation sperren"),
            ),
        ),
        Group(
            id = "app_kontrolle",
            description = "App-Kontrolle — sperrt Dritt-Dienste, die als Keylogger/Screen-Scraper " +
                "missbraucht werden könnten.",
            entries = listOf(
                Entry(AccessibilityLockdownSafeguard.ID, "Nur System-Bedienungshilfen erlauben"),
                Entry(InputMethodLockdownSafeguard.ID, "Nur System-Tastatur erlauben"),
            ),
        ),
        Group(
            id = "forensik",
            description = "Forensik/Audit — invasiver als die übrigen Schalter, deshalb bewusst " +
                "separat und standardmäßig aus.",
            entries = listOf(
                Entry(SecurityLoggingSafeguard.ID, "System-Sicherheitslog"),
                Entry(NetworkLoggingSafeguard.ID, "Netzwerk-Metadaten-Log"),
            ),
        ),
        Group(
            id = "passwort_backup",
            description = "Passwort/Backup — eigene Ergänzung (2026-08-22): starker Sperrbildschirm-" +
                "PIN nützt wenig ohne kurze Auto-Sperrzeit und ohne Schutz vor Datenabfluss " +
                "über Cloud-/adb-Backup.",
            entries = listOf(
                Entry(PasswordComplexitySafeguard.ID, "Starke Sperrbildschirm-PIN erzwingen"),
                Entry(AutoLockTimeoutSafeguard.ID, "Kurze Auto-Sperrzeit erzwingen (30s)"),
                Entry(BackupServiceLockdownSafeguard.ID, "Backup-Dienst sperren"),
            ),
        ),
        Group(
            id = "weitere_haertung",
            description = "Weitere Härtung — eigene Ergänzung, dritte Runde (2026-08-22).",
            entries = listOf(
                Entry(SystemUpdatePolicySafeguard.ID, "Sicherheitsupdates automatisch installieren"),
                Entry(
                    id = MtePolicySafeguard.ID,
                    label = "Speicher-Tagging (MTE) erzwingen",
                    supportingText = "Lässt die CPU Speicherfehler erkennen — die Fehlerklasse, " +
                        "aus der Exploit-Ketten gebaut werden. Wirkt erst nach einem Neustart. " +
                        "⚠ Braucht ARMv9-Hardware (Pixel 8+); auf allen anderen Geräten bleibt " +
                        "der Schalter wirkungslos und zeigt weiterhin „aus“.",
                ),
            ),
        ),
        Group(
            id = "funk_netz",
            description = "Funk & Netz — Angriffsfläche bei physischer Nähe (2026-08-28). Spürbare " +
                "Einschränkungen im Alltag, deshalb erst im Maximal-Profil enthalten.",
            entries = listOf(
                Entry(
                    id = UserRestrictionSafeguard.CELLULAR_2G_DISABLED_ID,
                    label = "2G/GSM-Rückfall verbieten",
                    supportingText = "2G authentisiert das Netz nicht — der Standardweg für " +
                        "IMSI-Catcher ist der erzwungene Downgrade. ⚠ In reinen 2G-Funklöchern " +
                        "fallen damit auch normale Anrufe/SMS aus; Notrufe bleiben laut Android " +
                        "ausgenommen.",
                ),
                Entry(
                    id = UserRestrictionSafeguard.NFC_RADIO_DISABLED_ID,
                    label = "NFC-Radio abschalten",
                    supportingText = "Gegen Relay-/Skimming-Angriffe aus nächster Nähe. ⚠ Damit " +
                        "endet auch kontaktloses Bezahlen und Transponder-Nutzung.",
                ),
                Entry(
                    id = UserRestrictionSafeguard.CONFIG_VPN_DISABLED_ID,
                    label = "VPN-Einrichtung sperren",
                    supportingText = "Verhindert, dass jemand mit kurzem Zugriff den gesamten " +
                        "Verkehr über einen fremden Endpunkt umleitet. ⚠ Auch du kannst dann kein " +
                        "eigenes VPN mehr anlegen oder ändern; bestehende Verbindungen laufen weiter.",
                ),
                Entry(
                    id = UserRestrictionSafeguard.BLUETOOTH_SHARING_DISABLED_ID,
                    label = "Bluetooth-Dateifreigabe sperren",
                    supportingText = "Nur der Dateikanal (OPP) — Kopfhörer, Uhr und " +
                        "Freisprecheinrichtung funktionieren weiter.",
                ),
            ),
        ),
    )

    /** Alle Einträge über alle Gruppen — für die Vollständigkeitsprüfung im Unit-Test. */
    val allEntries: List<Entry> = groups.flatMap { it.entries }
}
