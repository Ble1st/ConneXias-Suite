package de.ble1st.warden.registry

import android.content.Context
import android.os.UserManager

/**
 * Generischer [de.ble1st.warden.domain.registry.Safeguard] für einen einzelnen
 * `UserManager.DISALLOW_*`-Wert (Meilenstein C.2, Konzept Abschnitt 4: "Schalter: DISALLOW_*,
 * ..."), über `DevicePolicyManager.addUserRestriction`/`clearUserRestriction`/
 * `getUserRestrictions()`. Ein Objekt pro Restriction, nicht eine Klasse pro
 * `DISALLOW_*`-Konstante — dieselbe Klasse trägt seit Meilenstein C.5 auch das
 * Geräte-Lockdown-Bündel ([DeviceLockdownBundle]: `DISALLOW_SAFE_BOOT`/`DISALLOW_FACTORY_RESET`/
 * `DISALLOW_DEBUGGING_FEATURES`, s. die jeweiligen Companion-Factories unten).
 *
 * `restriction` ist eine der `UserManager.DISALLOW_*`-Konstanten; `id` ist der stabile
 * Registry-Bezeichner (s. `Safeguard.id`-Doc) — bewusst ein eigener Parameter statt `restriction`
 * direkt als `id` zu verwenden, damit die Registry-`id` stabil bliebe, selbst wenn sich die
 * zugrundeliegende Android-Konstante einmal ändern sollte.
 */
class UserRestrictionSafeguard(
    context: Context,
    private val restriction: String,
    override val id: String,
) : DpmSafeguard(context) {

    override fun apply() {
        devicePolicyManager().addUserRestriction(admin, restriction)
    }

    override fun revert() {
        devicePolicyManager().clearUserRestriction(admin, restriction)
    }

    override fun isActive(): Boolean =
        devicePolicyManager().getUserRestrictions(admin).getBoolean(restriction, false)

    companion object {
        /**
         * Meilenstein C.2 — der erste konkrete `DISALLOW_*`-Schalter. Bewusst **keine**
         * geräte-recoverability-relevante Restriction: `DISALLOW_FACTORY_RESET`/
         * `DISALLOW_SAFE_BOOT`/`DISALLOW_DEBUGGING_FEATURES` bleiben dem Geräte-Lockdown-Bündel
         * vorbehalten (Meilenstein C.5) und werden bewusst nicht schon hier als Erstbeispiel auf
         * dem echten Testgerät durchgetestet — ein abgebrochener Testlauf (Crash, Timeout, `adb`-
         * Verbindungsabbruch) könnte sonst mit `DISALLOW_FACTORY_RESET=true` enden, während der
         * Werksreset laut CLAUDE.md ("Rückbau ist NICHT trivial") aktuell der einzige bekannte
         * DO-Rückbauweg auf diesem Gerät ist. `DISALLOW_INSTALL_UNKNOWN_SOURCES` hat dagegen
         * keinerlei Bezug zur Wiederherstellbarkeit.
         */
        fun installUnknownSourcesDisabled(context: Context): UserRestrictionSafeguard =
            UserRestrictionSafeguard(
                context = context,
                restriction = UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                id = INSTALL_UNKNOWN_SOURCES_DISABLED_ID,
            )

        /** War bisher nur über [installUnknownSourcesDisabled] registriert (Registry/Reconciler/
         * MasterSwitch-Scope), ohne dass ihn irgendwer gezielt umschalten konnte — 2026-08-22
         * ("welche Funktionen können von GrapheneOS übernommen werden") als normaler Schalter in
         * [de.ble1st.warden.ui.SafeguardsScreen] ergänzt. **Kein** App-individuelles Whitelisting:
         * `DevicePolicyManager` bietet dafür keine öffentliche API auf Standard-/Samsung-Android
         * (GrapheneOS' Vorbild ist eine eigene Erweiterung von deren Permission-System, kein
         * AOSP-Standard) — dieser Schalter bleibt geräteweit an/aus, wie die zugrunde liegende
         * `DISALLOW_INSTALL_UNKNOWN_SOURCES`-Restriction es hergibt.
         */
        const val INSTALL_UNKNOWN_SOURCES_DISABLED_ID = "install_unknown_sources_disabled"

        /**
         * Everyday catalog entry (Alltag profile) **and** a [DeviceLockdownBundle] member.
         * Blocks factory reset from Settings (`DISALLOW_FACTORY_RESET`). Does **not** by itself
         * guarantee a Recovery wipe is impossible — that is OEM-dependent. Not `wipeData()` —
         * that stays an unwired stub.
         */
        fun factoryResetDisabled(context: Context): UserRestrictionSafeguard =
            UserRestrictionSafeguard(
                context = context,
                restriction = UserManager.DISALLOW_FACTORY_RESET,
                id = FACTORY_RESET_DISABLED_ID,
            )

        /**
         * Everyday catalog entry (Alltag profile) **and** a [DeviceLockdownBundle] member.
         * Blocks Safe Mode (`DISALLOW_SAFE_BOOT`), which is a common recovery-adjacent path to
         * disable third-party apps / weaken Device-Owner-adjacent protections.
         */
        fun safeBootDisabled(context: Context): UserRestrictionSafeguard =
            UserRestrictionSafeguard(
                context = context,
                restriction = UserManager.DISALLOW_SAFE_BOOT,
                id = SAFE_BOOT_DISABLED_ID,
            )

        /**
         * Meilenstein C.5 — ursprünglich nur Teil des Geräte-Lockdown-Bündels
         * ([DeviceLockdownBundle]). Seit "LockMode/Threat-Protection-Ausbau" (2026-08-25, auf
         * ausdrücklichen Nutzerwunsch: "als Schalter unter Safeguards") **zusätzlich** ein
         * eigenständiger, direkt umschaltbarer Alltags-Katalogeintrag (dasselbe
         * "auch als Einzelschalter"-Muster wie [factoryResetDisabled]/[safeBootDisabled]) —
         * `de.ble1st.warden.ui.SafeguardsScreen` verwendet dafür bewusst eine eigene
         * `ConfirmBeforeEnableEntryRow`-Zeile statt der ungegateten `SafeguardEntryRow`: anders
         * als bei den Reset-Pfad-Schaltern liegt das Risiko hier beim **Einschalten**, nicht beim
         * Ausschalten.
         *
         * **Weiterhin dieselbe Vorsicht wie zuvor, jetzt als UI-Warnhinweis statt nur als
         * Kommentar:** `DISALLOW_DEBUGGING_FEATURES` schaltet USB-Debugging/ADB selbst ab und
         * lässt sich laut Android-Dokumentation nicht mehr über die Entwickleroptionen
         * reaktivieren, solange die Restriction aktiv ist — auf einem per USB angeschlossenen
         * Entwicklungsgerät kappt das Aktivieren mutmaßlich die eigene `adb`-Verbindung, über die
         * der Testlauf überhaupt erst läuft. Deshalb weiterhin **bewusst nie in diesem Repo live
         * per `apply()` getestet** — ein realer Test auf einem Nicht-Entwicklungsgerät bleibt eine
         * bewusste, separate Entscheidung der Betreiberin, kein Nebeneffekt dieser Verkabelung.
         */
        fun debuggingFeaturesDisabled(context: Context): UserRestrictionSafeguard =
            UserRestrictionSafeguard(
                context = context,
                restriction = UserManager.DISALLOW_DEBUGGING_FEATURES,
                id = DEBUGGING_FEATURES_DISABLED_ID,
            )

        /** Stabile Registry-`id` von [debuggingFeaturesDisabled] — als Konstante exponiert,
         * dasselbe Muster wie [CONFIG_DATE_TIME_DISABLED_ID]. */
        const val DEBUGGING_FEATURES_DISABLED_ID = "debugging_features_disabled"

        /**
         * Tier 1 ("Anti-Tamper", 2026-08-22): schützt die Anti-Hammering-Backoff-Zeitfenster
         * ([de.ble1st.warden.domain.pin.WardenAntiHammeringDecision]) und die Zeitstempel im
         * [de.ble1st.warden.logging.HashChainLogStore] vor Manipulation über die Systemuhr.
         * Anders als `DISALLOW_SAFE_BOOT`/`DISALLOW_FACTORY_RESET`/`DISALLOW_DEBUGGING_FEATURES`
         * **kein** Rückbau-/adb-Risiko (die Restriction lässt sich jederzeit über dieselbe API
         * wieder zurücknehmen, blockiert weder Werksreset noch USB-Debugging) — deshalb bewusst
         * *nicht* Teil von [DeviceLockdownBundle] und regulär über [ui.SafeguardsScreen]
         * umschaltbar statt dauerhaft dormant.
         */
        fun configDateTimeDisabled(context: Context): UserRestrictionSafeguard =
            UserRestrictionSafeguard(
                context = context,
                restriction = UserManager.DISALLOW_CONFIG_DATE_TIME,
                id = CONFIG_DATE_TIME_DISABLED_ID,
            )

        /**
         * Vierte Ergänzungsrunde ("weitere Härtung", 2026-08-22) — Mikrofon-Pendant zu
         * [CameraSafeguard]: `UserManager.DISALLOW_UNMUTE_MICROPHONE` hält das Mikrofon dauerhaft
         * stummgeschaltet, dieselbe Anti-Überwachungs-Logik wie die bereits vorhandene
         * Kamerasperre. Bewusst als Top-Level-Schalter neben Kamera/Bildschirmaufnahme in
         * [ui.SafeguardsScreen] platziert, nicht in einer der Unterkategorien — konzeptionell
         * derselbe "Sensor hart sperren"-Fall wie die beiden bestehenden Schalter dort.
         */
        fun microphoneMuted(context: Context): UserRestrictionSafeguard =
            UserRestrictionSafeguard(
                context = context,
                restriction = UserManager.DISALLOW_UNMUTE_MICROPHONE,
                id = MICROPHONE_MUTED_ID,
            )

        /**
         * Vierte Ergänzungsrunde ("weitere Härtung", 2026-08-22) — verhindert, dass über
         * Einstellungen > Sicherheit neue Zertifikate/Anmeldedaten installiert werden
         * (`DISALLOW_CONFIG_CREDENTIALS`). Schließt einen klassischen Man-in-the-Middle-Weg:
         * ohne diese Sperre könnte jemand mit kurzem physischem Zugriff ein Rogue-CA-Zertifikat
         * installieren und damit TLS-Verbindungen des Geräts abhören. Passt zur Tier-1-Linie
         * (Manipulation am Gerät selbst verhindern), reversibel, kein Rückbau-Risiko.
         */
        fun credentialConfigDisabled(context: Context): UserRestrictionSafeguard =
            UserRestrictionSafeguard(
                context = context,
                restriction = UserManager.DISALLOW_CONFIG_CREDENTIALS,
                id = CREDENTIAL_CONFIG_DISABLED_ID,
            )

        /**
         * Vierte Ergänzungsrunde ("weitere Härtung", 2026-08-22) — verhindert das Einhängen
         * externer Datenträger (SD-Karte/USB-Massenspeicher, `DISALLOW_MOUNT_PHYSICAL_MEDIA`).
         * Ergänzt [BackupServiceLockdownSafeguard] um einen zweiten Datenabfluss-Weg: nicht nur
         * Cloud-/adb-Backup, sondern auch das simple Kopieren auf einen angeschlossenen
         * USB-Stick. Betrifft **nicht** USB-Debugging/adb selbst (andere Restriction als
         * `DISALLOW_DEBUGGING_FEATURES`), deshalb kein adb-Abbruch-Risiko wie beim dormanten
         * Lockdown-Bündel.
         */
        fun physicalMediaMountDisabled(context: Context): UserRestrictionSafeguard =
            UserRestrictionSafeguard(
                context = context,
                restriction = UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
                id = PHYSICAL_MEDIA_MOUNT_DISABLED_ID,
            )

        /**
         * Blocks adding or removing accounts in Settings (`DISALLOW_MODIFY_ACCOUNTS`). General
         * account-tampering hardening (no rogue account added, no unauthorized account removed)
         * rather than a direct technical dependency of [FactoryResetProtectionSafeguard]: the
         * enterprise FRP unlock-account list is set explicitly via
         * `DevicePolicyManager.setFactoryResetProtectionPolicy` and persisted in GMS, not derived
         * from the device's live `AccountManager` state, so removing an account in Settings does
         * not by itself change an already-applied FRP policy. Kept here as defense-in-depth
         * alongside FRP rather than as its enforcement mechanism.
         */
        fun modifyAccountsDisabled(context: Context): UserRestrictionSafeguard =
            UserRestrictionSafeguard(
                context = context,
                restriction = UserManager.DISALLOW_MODIFY_ACCOUNTS,
                id = MODIFY_ACCOUNTS_DISABLED_ID,
            )

        const val MICROPHONE_MUTED_ID = "microphone_muted"
        const val CREDENTIAL_CONFIG_DISABLED_ID = "credential_config_disabled"
        const val PHYSICAL_MEDIA_MOUNT_DISABLED_ID = "physical_media_mount_disabled"
        const val FACTORY_RESET_DISABLED_ID = "factory_reset_disabled"
        const val SAFE_BOOT_DISABLED_ID = "safe_boot_disabled"
        const val MODIFY_ACCOUNTS_DISABLED_ID = "modify_accounts_disabled"

        /** Stabile Registry-`id` von [configDateTimeDisabled] — als Konstante exponiert, damit
         * UI-Aufrufer (z. B. [de.ble1st.warden.ui.WardenStatusActivity]) sie nicht als
         * String-Literal duplizieren müssen, dasselbe Muster wie `CameraSafeguard.ID` & Co. */
        const val CONFIG_DATE_TIME_DISABLED_ID = "config_date_time_disabled"

        /**
         * "Fehlende Restriction-Abdeckung" (2026-08-28, aus der Lückenanalyse): ein zusätzlich
         * angelegter Nutzer (Gast oder Zweitprofil) ist ein klassischer Umgehungsweg — die
         * meisten der übrigen Schalter hier wirken nutzerbezogen, ein frisches Profil startet
         * ungehärtet und hat trotzdem Zugriff auf dasselbe Gerät. `DISALLOW_ADD_USER` schließt
         * das, ohne irgendetwas am Alltagsbetrieb des Hauptnutzers zu ändern — deshalb bereits
         * im Alltag-Profil und nicht erst in Reise/Maximal.
         */
        fun addUserDisabled(context: Context): UserRestrictionSafeguard =
            UserRestrictionSafeguard(
                context = context,
                restriction = UserManager.DISALLOW_ADD_USER,
                id = ADD_USER_DISABLED_ID,
            )

        /**
         * `DISALLOW_CELLULAR_2G` (Android 14+) — verbietet den Rückfall auf 2G/GSM. 2G kennt
         * keine gegenseitige Authentisierung des Netzes und ist damit der Standardweg für
         * IMSI-Catcher: ein gefälschter Mast zwingt das Gerät auf 2G herunter und liest von dort
         * an Verkehr und Standort mit. Dieselbe Überlegung wie GrapheneOS' "2G abschalten"-
         * Schalter, hier über die offizielle Device-Owner-Restriction statt einer ROM-Erweiterung.
         *
         * **Nur im Maximal-Profil**, nicht in Reise: in Gegenden mit ausschließlich 2G-Abdeckung
         * fallen damit auch normale Anrufe/SMS aus. Notrufe bleiben laut Android-Dokumentation
         * unberührt — die Restriction gilt ausdrücklich nicht für `emergency calls`.
         */
        fun cellular2gDisabled(context: Context): UserRestrictionSafeguard =
            UserRestrictionSafeguard(
                context = context,
                restriction = UserManager.DISALLOW_CELLULAR_2G,
                id = CELLULAR_2G_DISABLED_ID,
            )

        /**
         * `DISALLOW_CONFIG_VPN` — verhindert, dass jemand mit kurzzeitigem physischem Zugriff
         * eine eigene VPN-Verbindung einrichtet und damit den gesamten Verkehr des Geräts über
         * einen fremden Endpunkt umleitet. Bewusst nur im Maximal-Profil: wer selbst ein VPN
         * nutzt, sperrt sich damit aus der eigenen Konfiguration aus (bestehende Verbindungen
         * laufen weiter, nur Anlegen/Ändern ist blockiert).
         */
        fun configVpnDisabled(context: Context): UserRestrictionSafeguard =
            UserRestrictionSafeguard(
                context = context,
                restriction = UserManager.DISALLOW_CONFIG_VPN,
                id = CONFIG_VPN_DISABLED_ID,
            )

        /**
         * `DISALLOW_USB_FILE_TRANSFER` — blockiert MTP/PTP-Dateiübertragung über USB.
         * Ergänzt [UsbDataSignalingSafeguard] (kappt USB-Daten vollständig) und
         * [de.ble1st.warden.usb.UsbAutoLockController] (kappt sie nur bei gesperrtem Bildschirm)
         * um die schwächste, alltagstauglichste Stufe: Laden und Zubehör funktionieren weiter,
         * nur der Dateizugriff fällt weg. Deshalb schon im Reise-Profil.
         */
        fun usbFileTransferDisabled(context: Context): UserRestrictionSafeguard =
            UserRestrictionSafeguard(
                context = context,
                restriction = UserManager.DISALLOW_USB_FILE_TRANSFER,
                id = USB_FILE_TRANSFER_DISABLED_ID,
            )

        /**
         * `DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO` — schaltet das NFC-Radio ab. Angriffsfläche
         * bei physischer Nähe (Relay-/Skimming-Angriffe auf Zahlungs- und Ausweisanwendungen,
         * unbemerktes Auslesen im gesperrten Zustand). Nur im Maximal-Profil, weil damit auch
         * kontaktloses Bezahlen und Transponder-Nutzung ausfallen.
         */
        fun nfcRadioDisabled(context: Context): UserRestrictionSafeguard =
            UserRestrictionSafeguard(
                context = context,
                restriction = UserManager.DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO,
                id = NFC_RADIO_DISABLED_ID,
            )

        /**
         * `DISALLOW_BLUETOOTH_SHARING` — verbietet das Teilen von Dateien per Bluetooth
         * (OPP-Profil). Bewusst **nicht** `DISALLOW_BLUETOOTH` (das ganze Radio aus): Kopfhörer,
         * Uhr und Freisprecheinrichtung sollen weiter funktionieren, der Dateiabfluss-Kanal nicht.
         * Reise-Profil.
         */
        fun bluetoothSharingDisabled(context: Context): UserRestrictionSafeguard =
            UserRestrictionSafeguard(
                context = context,
                restriction = UserManager.DISALLOW_BLUETOOTH_SHARING,
                id = BLUETOOTH_SHARING_DISABLED_ID,
            )

        /**
         * **Bewusst nicht aufgenommen** (2026-08-28, aus derselben Analyse):
         * - `DISALLOW_AIRPLANE_MODE`: soll verhindern, dass ein Dieb das Gerät offline nimmt —
         *   nützt hier nichts, weil Warden ohnehin keinen Fernkanal hat, der davon profitieren
         *   würde. Reiner Komfortverlust ohne Schutzgewinn.
         * - `DISALLOW_SIM_GLOBALLY`: sperrt jede SIM-Nutzung geräteweit. Zu grob als Schalter
         *   neben den übrigen — der eigentlich gemeinte Fall (fremde SIM eingelegt) wird von
         *   [de.ble1st.warden.sim.SimChangeController] behandelt, der reagiert statt pauschal zu
         *   verbieten.
         */
        const val ADD_USER_DISABLED_ID = "add_user_disabled"
        const val CELLULAR_2G_DISABLED_ID = "cellular_2g_disabled"
        const val CONFIG_VPN_DISABLED_ID = "config_vpn_disabled"
        const val USB_FILE_TRANSFER_DISABLED_ID = "usb_file_transfer_disabled"
        const val NFC_RADIO_DISABLED_ID = "nfc_radio_disabled"
        const val BLUETOOTH_SHARING_DISABLED_ID = "bluetooth_sharing_disabled"
    }
}
