package de.ble1st.warden.logging

import android.app.admin.ConnectEvent
import android.app.admin.DnsEvent
import android.app.admin.NetworkEvent
import android.app.admin.SecurityLog
import android.os.SystemClock
import de.ble1st.warden.domain.securitylog.SecurityLogEventType
import de.ble1st.warden.domain.securitylog.SecurityLogRecord

/**
 * Android-Seite der System-Ereignis-Auswertung (2026-08-28): wandelt
 * `SecurityLog.SecurityEvent`/[NetworkEvent] in die framework-freien [SecurityLogRecord]s um, die
 * [SecurityEventStore] speichert und der Log-Viewer anzeigt.
 *
 * **Zeitstempel-Umrechnung, nicht nur Übernahme:** `SecurityEvent.getTimeNanos()` zählt seit dem
 * letzten Boot, nicht seit der Epoche — direkt gespeichert stünde in der Anzeige ein Datum aus dem
 * Jahr 1970. Umgerechnet wird über die Differenz zur aktuellen `elapsedRealtimeNanos()`, also
 * relativ zum Abrufzeitpunkt; ein Ereignis von vor einer Stunde bekommt damit auch dann die
 * richtige Uhrzeit, wenn der Batch erst jetzt zugestellt wird.
 *
 * **Unbekannte Tags werden zu [SecurityLogEventType.SONSTIGES] statt verworfen** — Android
 * ergänzt die Tag-Liste mit neuen Versionen, und ein Ereignis, das diese Zuordnung noch nicht
 * kennt, ist immer noch aussagekräftiger als eine Lücke im Protokoll.
 */
object SecurityEventParser {

    fun parseSecurityEvents(events: List<SecurityLog.SecurityEvent>): List<SecurityLogRecord> {
        val nowMillis = System.currentTimeMillis()
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        return events.map { event ->
            SecurityLogRecord(
                timestampMillis = nowMillis - (nowNanos - event.timeNanos) / NANOS_PER_MILLI,
                type = typeOf(event.tag),
                detail = describe(event.data),
            )
        }
    }

    fun parseNetworkEvents(events: List<NetworkEvent>): List<SecurityLogRecord> {
        val nowMillis = System.currentTimeMillis()
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        return events.map { event ->
            val timestamp = nowMillis - (nowNanos - event.timestamp * NANOS_PER_MILLI) / NANOS_PER_MILLI
            when (event) {
                is DnsEvent -> SecurityLogRecord(
                    timestampMillis = timestamp,
                    type = SecurityLogEventType.DNS_AUFLOESUNG,
                    detail = "${event.packageName}: ${event.hostname} -> ${event.inetAddresses.joinToString { it.hostAddress.orEmpty() }}",
                )
                is ConnectEvent -> SecurityLogRecord(
                    timestampMillis = timestamp,
                    type = SecurityLogEventType.NETZWERKVERBINDUNG,
                    detail = "${event.packageName}: ${event.inetAddress.hostAddress.orEmpty()}:${event.port}",
                )
                else -> SecurityLogRecord(
                    timestampMillis = timestamp,
                    type = SecurityLogEventType.SONSTIGES,
                    detail = event.packageName.orEmpty(),
                )
            }
        }
    }

    private fun typeOf(tag: Int): SecurityLogEventType = when (tag) {
        SecurityLog.TAG_ADB_SHELL_CMD -> SecurityLogEventType.ADB_SHELL_KOMMANDO
        SecurityLog.TAG_ADB_SHELL_INTERACTIVE -> SecurityLogEventType.ADB_SHELL_INTERAKTIV
        SecurityLog.TAG_SYNC_RECV_FILE -> SecurityLogEventType.ADB_DATEI_EMPFANGEN
        SecurityLog.TAG_SYNC_SEND_FILE -> SecurityLogEventType.ADB_DATEI_GESENDET
        SecurityLog.TAG_CERT_AUTHORITY_INSTALLED -> SecurityLogEventType.ZERTIFIKATSSTELLE_INSTALLIERT
        SecurityLog.TAG_CERT_AUTHORITY_REMOVED -> SecurityLogEventType.ZERTIFIKATSSTELLE_ENTFERNT
        SecurityLog.TAG_CERT_VALIDATION_FAILURE -> SecurityLogEventType.ZERTIFIKATSPRUEFUNG_FEHLGESCHLAGEN
        SecurityLog.TAG_KEY_INTEGRITY_VIOLATION -> SecurityLogEventType.SCHLUESSEL_INTEGRITAET_VERLETZT
        SecurityLog.TAG_WIPE_FAILURE -> SecurityLogEventType.WIPE_FEHLGESCHLAGEN
        SecurityLog.TAG_KEYGUARD_DISMISS_AUTH_ATTEMPT -> SecurityLogEventType.ENTSPERRVERSUCH
        SecurityLog.TAG_KEYGUARD_DISMISSED -> SecurityLogEventType.SPERRE_AUFGEHOBEN
        SecurityLog.TAG_KEYGUARD_SECURED -> SecurityLogEventType.SPERRE_AKTIV
        SecurityLog.TAG_PACKAGE_INSTALLED -> SecurityLogEventType.PAKET_INSTALLIERT
        SecurityLog.TAG_PACKAGE_UPDATED -> SecurityLogEventType.PAKET_AKTUALISIERT
        SecurityLog.TAG_PACKAGE_UNINSTALLED -> SecurityLogEventType.PAKET_DEINSTALLIERT
        SecurityLog.TAG_USER_RESTRICTION_ADDED -> SecurityLogEventType.NUTZER_RESTRIKTION_HINZUGEFUEGT
        SecurityLog.TAG_USER_RESTRICTION_REMOVED -> SecurityLogEventType.NUTZER_RESTRIKTION_ENTFERNT
        SecurityLog.TAG_PASSWORD_CHANGED -> SecurityLogEventType.PASSWORT_GEAENDERT
        SecurityLog.TAG_REMOTE_LOCK -> SecurityLogEventType.FERNSPERRE
        SecurityLog.TAG_MEDIA_MOUNT -> SecurityLogEventType.MEDIUM_EINGEHAENGT
        SecurityLog.TAG_MEDIA_UNMOUNT -> SecurityLogEventType.MEDIUM_AUSGEHAENGT
        SecurityLog.TAG_APP_PROCESS_START -> SecurityLogEventType.PROZESS_GESTARTET
        SecurityLog.TAG_OS_STARTUP -> SecurityLogEventType.SYSTEM_START
        SecurityLog.TAG_OS_SHUTDOWN -> SecurityLogEventType.SYSTEM_HERUNTERGEFAHREN
        SecurityLog.TAG_LOGGING_STARTED -> SecurityLogEventType.LOGGING_GESTARTET
        SecurityLog.TAG_LOGGING_STOPPED -> SecurityLogEventType.LOGGING_GESTOPPT
        SecurityLog.TAG_CRYPTO_SELF_TEST_COMPLETED -> SecurityLogEventType.KRYPTO_SELBSTTEST
        SecurityLog.TAG_CAMERA_POLICY_SET,
        SecurityLog.TAG_PASSWORD_COMPLEXITY_SET,
        SecurityLog.TAG_MAX_PASSWORD_ATTEMPTS_SET,
        SecurityLog.TAG_MAX_SCREEN_LOCK_TIMEOUT_SET,
        SecurityLog.TAG_KEYGUARD_DISABLED_FEATURES_SET,
        -> SecurityLogEventType.RICHTLINIE_GESETZT
        else -> SecurityLogEventType.SONSTIGES
    }

    /** `SecurityEvent.getData()` liefert je nach Tag einen String, eine Zahl oder ein Array —
     * Arrays werden flach zusammengesetzt, damit die Anzeige nicht `[Ljava.lang.Object;@…` zeigt. */
    private fun describe(data: Any?): String = when (data) {
        null -> ""
        is Array<*> -> data.joinToString(" ") { describe(it) }
        else -> data.toString()
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
