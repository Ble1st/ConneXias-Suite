package de.ble1st.warden.domain.config

/**
 * Text-Kodierung für [WardenConfigSnapshot] (2026-09-03) — bewusst ein einfaches
 * `schluessel=wert`-Zeilenformat statt eines Binärformats: die Export-Datei soll ein Mensch auch
 * ohne Warden öffnen und überfliegen können (z. B. um vor einem Import zu prüfen, was er
 * enthält), dieselbe Überlegung wie bei den übrigen Klartext-Speicherorten im Projekt (z. B.
 * [de.ble1st.warden.pin.WardenLockScreenTextStorage]s Begründung für Klartext statt Envelope).
 *
 * Kein JSON: `org.json` ist auf einer reinen JVM (JVM-Unit-Tests dieses Moduls, ohne Robolectric)
 * nicht verfügbar (die Android-Variante wirft dort zur Laufzeit), eine eigene Kotlin-Bibliothek
 * wäre für ein derart kleines, flaches Schema unverhältnismäßig.
 *
 * Werte werden escaped (`\` -> `\\`, Zeilenumbruch -> `\n`), damit ein einzelnes Feld (z. B. ein
 * mehrzeiliger Support-Hinweistext) nie über mehrere Zeilen zerreißt oder mit dem nächsten
 * Schlüssel kollidiert. [decode] überspringt unbekannte Zeilen/Schlüssel statt zu scheitern — eine
 * künftig ältere/neuere Warden-Version tauscht Export-Dateien so verlustarm aus, statt beim
 * kleinsten Versionsunterschied komplett zu verweigern (dieselbe "einzelne fehlerhafte Zeile
 * überspringen, nicht die ganze Datei verwerfen"-Haltung wie
 * [de.ble1st.warden.logging.SecurityLogCodec], mit derselben dort dokumentierten bewussten
 * Ausnahme vom sonst geltenden Fail-Safe-Prinzip).
 */
object WardenConfigCodec {
    const val VERSION = 1

    private const val KEY_VERSION = "version"
    private const val KEY_SAFEGUARD_PREFIX = "safeguard."
    private const val KEY_EFFECTIVE_PROFILE = "effective_profile"
    private const val KEY_CLIPBOARD_GUARD_ENABLED = "clipboard_guard_enabled"
    private const val KEY_CLIPBOARD_GUARD_THRESHOLD_MILLIS = "clipboard_guard_threshold_millis"
    private const val KEY_CLIPBOARD_CROSS_APP_MONITORING_ENABLED = "clipboard_cross_app_monitoring_enabled"
    private const val KEY_SIM_CHANGE_REACTION = "sim_change_reaction"
    private const val KEY_CELL_SECURITY_REACTION = "cell_security_reaction"
    private const val KEY_WIFI_TRUST_REACTION = "wifi_trust_reaction"
    private const val KEY_TRUSTED_WIFI_SSID_PREFIX = "trusted_wifi_ssid."
    private const val KEY_ANTI_THEFT_MOTION_ALARM_ENABLED = "anti_theft_motion_alarm_enabled"
    private const val KEY_ANTI_THEFT_CHARGER_ALARM_ENABLED = "anti_theft_charger_alarm_enabled"
    private const val KEY_LOCK_SCREEN_TEXT = "lock_screen_text"
    private const val KEY_ORGANIZATION_NAME = "organization_name"
    private const val KEY_SUPPORT_MESSAGE = "support_message"
    private const val KEY_AUTO_REBOOT_THRESHOLD_HOURS = "auto_reboot_threshold_hours"
    private const val KEY_FAILED_ATTEMPTS_REBOOT_THRESHOLD = "failed_attempts_reboot_threshold"
    private const val KEY_AUTO_PROFILE_NIGHT_PROFILE = "auto_profile_night_profile"
    private const val KEY_AUTO_PROFILE_DAY_PROFILE = "auto_profile_day_profile"
    private const val KEY_AUTO_PROFILE_NIGHT_START_MINUTE = "auto_profile_night_start_minute"
    private const val KEY_AUTO_PROFILE_NIGHT_END_MINUTE = "auto_profile_night_end_minute"
    private const val KEY_AUTO_PROFILE_ESCALATE = "auto_profile_escalate_on_critical_threat"

    fun encode(snapshot: WardenConfigSnapshot): String = buildString {
        appendLine("$KEY_VERSION=$VERSION")
        for ((id, active) in snapshot.safeguardActiveState) {
            appendLine("$KEY_SAFEGUARD_PREFIX${escape(id)}=$active")
        }
        snapshot.effectiveProfile?.let { appendLine("$KEY_EFFECTIVE_PROFILE=$it") }
        appendLine("$KEY_CLIPBOARD_GUARD_ENABLED=${snapshot.clipboardGuardEnabled}")
        appendLine("$KEY_CLIPBOARD_GUARD_THRESHOLD_MILLIS=${snapshot.clipboardGuardThresholdMillis}")
        appendLine("$KEY_CLIPBOARD_CROSS_APP_MONITORING_ENABLED=${snapshot.clipboardCrossAppMonitoringEnabled}")
        snapshot.simChangeReaction?.let { appendLine("$KEY_SIM_CHANGE_REACTION=$it") }
        snapshot.cellSecurityReaction?.let { appendLine("$KEY_CELL_SECURITY_REACTION=$it") }
        snapshot.wifiTrustReaction?.let { appendLine("$KEY_WIFI_TRUST_REACTION=$it") }
        snapshot.trustedWifiSsids.forEachIndexed { index, ssid ->
            appendLine("$KEY_TRUSTED_WIFI_SSID_PREFIX$index=${escape(ssid)}")
        }
        appendLine("$KEY_ANTI_THEFT_MOTION_ALARM_ENABLED=${snapshot.antiTheftMotionAlarmEnabled}")
        appendLine("$KEY_ANTI_THEFT_CHARGER_ALARM_ENABLED=${snapshot.antiTheftChargerAlarmEnabled}")
        snapshot.lockScreenText?.let { appendLine("$KEY_LOCK_SCREEN_TEXT=${escape(it)}") }
        snapshot.organizationName?.let { appendLine("$KEY_ORGANIZATION_NAME=${escape(it)}") }
        snapshot.supportMessage?.let { appendLine("$KEY_SUPPORT_MESSAGE=${escape(it)}") }
        snapshot.autoRebootThresholdHours?.let { appendLine("$KEY_AUTO_REBOOT_THRESHOLD_HOURS=$it") }
        snapshot.failedAttemptsRebootThreshold?.let { appendLine("$KEY_FAILED_ATTEMPTS_REBOOT_THRESHOLD=$it") }
        snapshot.autoProfileNightProfile?.let { appendLine("$KEY_AUTO_PROFILE_NIGHT_PROFILE=$it") }
        snapshot.autoProfileDayProfile?.let { appendLine("$KEY_AUTO_PROFILE_DAY_PROFILE=$it") }
        snapshot.autoProfileNightStartMinuteOfDay?.let { appendLine("$KEY_AUTO_PROFILE_NIGHT_START_MINUTE=$it") }
        snapshot.autoProfileNightEndMinuteOfDay?.let { appendLine("$KEY_AUTO_PROFILE_NIGHT_END_MINUTE=$it") }
        appendLine("$KEY_AUTO_PROFILE_ESCALATE=${snapshot.autoProfileEscalateOnCriticalThreat}")
    }

    /** Wirft nie — eine unlesbare/leere Datei liefert einfach ein `WardenConfigSnapshot()` mit
     * lauter Defaults; unbekannte oder beschädigte einzelne Zeilen werden übersprungen (s.
     * Klassendoc). Ein Import-Aufrufer erkennt "praktisch nichts enthalten" selbst am Ergebnis,
     * ohne dass der Codec dafür einen eigenen Fehlerpfad bräuchte. */
    fun decode(text: String): WardenConfigSnapshot {
        val safeguards = mutableMapOf<String, Boolean>()
        val ssids = sortedMapOf<Int, String>()
        var effectiveProfile: String? = null
        var clipboardGuardEnabled = false
        var clipboardGuardThresholdMillis = 0L
        var clipboardCrossAppMonitoringEnabled = false
        var simChangeReaction: String? = null
        var cellSecurityReaction: String? = null
        var wifiTrustReaction: String? = null
        var antiTheftMotionAlarmEnabled = false
        var antiTheftChargerAlarmEnabled = false
        var lockScreenText: String? = null
        var organizationName: String? = null
        var supportMessage: String? = null
        var autoRebootThresholdHours: Int? = null
        var failedAttemptsRebootThreshold: Int? = null
        var autoProfileNightProfile: String? = null
        var autoProfileDayProfile: String? = null
        var autoProfileNightStartMinuteOfDay: Int? = null
        var autoProfileNightEndMinuteOfDay: Int? = null
        var autoProfileEscalateOnCriticalThreat = false

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val separatorIndex = line.indexOf('=')
            if (separatorIndex < 0) continue
            val key = line.substring(0, separatorIndex)
            val value = line.substring(separatorIndex + 1)
            when {
                key == KEY_VERSION -> Unit
                key.startsWith(KEY_SAFEGUARD_PREFIX) ->
                    value.toBooleanStrictOrNull()?.let { safeguards[unescape(key.removePrefix(KEY_SAFEGUARD_PREFIX))] = it }
                key == KEY_EFFECTIVE_PROFILE -> effectiveProfile = value
                key == KEY_CLIPBOARD_GUARD_ENABLED -> clipboardGuardEnabled = value.toBooleanStrictOrNull() ?: false
                key == KEY_CLIPBOARD_GUARD_THRESHOLD_MILLIS -> clipboardGuardThresholdMillis = value.toLongOrNull() ?: 0L
                key == KEY_CLIPBOARD_CROSS_APP_MONITORING_ENABLED -> clipboardCrossAppMonitoringEnabled = value.toBooleanStrictOrNull() ?: false
                key == KEY_SIM_CHANGE_REACTION -> simChangeReaction = value
                key == KEY_CELL_SECURITY_REACTION -> cellSecurityReaction = value
                key == KEY_WIFI_TRUST_REACTION -> wifiTrustReaction = value
                key.startsWith(KEY_TRUSTED_WIFI_SSID_PREFIX) ->
                    key.removePrefix(KEY_TRUSTED_WIFI_SSID_PREFIX).toIntOrNull()?.let { ssids[it] = unescape(value) }
                key == KEY_ANTI_THEFT_MOTION_ALARM_ENABLED -> antiTheftMotionAlarmEnabled = value.toBooleanStrictOrNull() ?: false
                key == KEY_ANTI_THEFT_CHARGER_ALARM_ENABLED -> antiTheftChargerAlarmEnabled = value.toBooleanStrictOrNull() ?: false
                key == KEY_LOCK_SCREEN_TEXT -> lockScreenText = unescape(value)
                key == KEY_ORGANIZATION_NAME -> organizationName = unescape(value)
                key == KEY_SUPPORT_MESSAGE -> supportMessage = unescape(value)
                key == KEY_AUTO_REBOOT_THRESHOLD_HOURS -> autoRebootThresholdHours = value.toIntOrNull()
                key == KEY_FAILED_ATTEMPTS_REBOOT_THRESHOLD -> failedAttemptsRebootThreshold = value.toIntOrNull()
                key == KEY_AUTO_PROFILE_NIGHT_PROFILE -> autoProfileNightProfile = value
                key == KEY_AUTO_PROFILE_DAY_PROFILE -> autoProfileDayProfile = value
                key == KEY_AUTO_PROFILE_NIGHT_START_MINUTE -> autoProfileNightStartMinuteOfDay = value.toIntOrNull()
                key == KEY_AUTO_PROFILE_NIGHT_END_MINUTE -> autoProfileNightEndMinuteOfDay = value.toIntOrNull()
                key == KEY_AUTO_PROFILE_ESCALATE -> autoProfileEscalateOnCriticalThreat = value.toBooleanStrictOrNull() ?: false
                // Unbekannter Schlüssel (künftige Version, oder Tippfehler) — übersprungen statt
                // die ganze Datei zu verwerfen, s. Klassendoc.
                else -> Unit
            }
        }

        return WardenConfigSnapshot(
            safeguardActiveState = safeguards,
            effectiveProfile = effectiveProfile,
            clipboardGuardEnabled = clipboardGuardEnabled,
            clipboardGuardThresholdMillis = clipboardGuardThresholdMillis,
            clipboardCrossAppMonitoringEnabled = clipboardCrossAppMonitoringEnabled,
            simChangeReaction = simChangeReaction,
            cellSecurityReaction = cellSecurityReaction,
            wifiTrustReaction = wifiTrustReaction,
            trustedWifiSsids = ssids.values.toSet(),
            antiTheftMotionAlarmEnabled = antiTheftMotionAlarmEnabled,
            antiTheftChargerAlarmEnabled = antiTheftChargerAlarmEnabled,
            lockScreenText = lockScreenText,
            organizationName = organizationName,
            supportMessage = supportMessage,
            autoRebootThresholdHours = autoRebootThresholdHours,
            failedAttemptsRebootThreshold = failedAttemptsRebootThreshold,
            autoProfileNightProfile = autoProfileNightProfile,
            autoProfileDayProfile = autoProfileDayProfile,
            autoProfileNightStartMinuteOfDay = autoProfileNightStartMinuteOfDay,
            autoProfileNightEndMinuteOfDay = autoProfileNightEndMinuteOfDay,
            autoProfileEscalateOnCriticalThreat = autoProfileEscalateOnCriticalThreat,
        )
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\n", "\\n")

    private fun unescape(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    'n' -> { append('\n'); i += 2 }
                    '\\' -> { append('\\'); i += 2 }
                    else -> { append(c); i++ }
                }
            } else {
                append(c)
                i++
            }
        }
    }
}
