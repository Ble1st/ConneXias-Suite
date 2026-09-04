package de.ble1st.warden.domain.appmanagement

/**
 * "Threat Alerts & Severity Levels" (2026-08-25, auf Nutzerwunsch, Feature-Ideenliste Punkt 0:
 * "Klassifizierung nach Kritikalität (Info/Warning/Critical), Farbcodierung und unterschiedliche
 * Notification-Typen"). Reine Werte-Zuordnung, kein Android-Bezug — dieselbe
 * "Entscheidung/Ausführung getrennt"-Haltung wie [SuspiciousAppScanDecision] & Co.
 *
 * **Zuordnung, keine Erfindung neuer Bedeutung:** ein bloß im Manifest *deklariertes* Vermögen
 * (z. B. [SuspiciousSignal.OVERLAY_PERMISSION_DECLARED]/[SuspiciousSignal.UNKNOWN_INSTALL_SOURCE])
 * ist häufig auch bei harmlosen, false-positive-anfälligen Fällen präsent (viele legitime Apps
 * fordern Overlay-Rechte; ein Sideload außerhalb des Play Store ist für ein Device-Owner-Projekt
 * wie dieses selbst der Normalfall) — [INFO]. Ein Vermögen, das bereits *aktiviert* wurde oder
 * eine unmittelbar riskante Fähigkeit ohne breite legitime Nutzung darstellt
 * ([SuspiciousSignal.EXTRA_DEVICE_ADMIN]/[SuspiciousSignal.ACCESSIBILITY_SERVICE_DECLARED]/
 * [SuspiciousSignal.NOTIFICATION_LISTENER_DECLARED]) — [WARNING]. Ein Übergang, der bereits *im
 * Gange* ist (Aktivierung zwischen zwei Scans, Signatur-/Versionssprung eines vorher schon
 * gesehenen Pakets) lässt sich nicht mehr als bloße Möglichkeit lesen, sondern als tatsächlich
 * beobachtetes Verhalten — [CRITICAL].
 *
 * [highest] entscheidet für einen Fund mit mehreren gleichzeitigen Signalen (dasselbe
 * "worst-signal-wins"-Prinzip wie farbliche Ampel-UIs sonst auch) — ein Fund ist nie
 * "durchschnittlich" gefährlich, sondern mindestens so gefährlich wie sein schlimmstes Signal.
 */
enum class ThreatSeverity {
    INFO,
    WARNING,
    CRITICAL,
    ;

    companion object {
        fun of(signal: SuspiciousSignal): ThreatSeverity = when (signal) {
            SuspiciousSignal.OVERLAY_PERMISSION_DECLARED,
            SuspiciousSignal.UNKNOWN_INSTALL_SOURCE,
            -> INFO

            SuspiciousSignal.EXTRA_DEVICE_ADMIN,
            SuspiciousSignal.ACCESSIBILITY_SERVICE_DECLARED,
            SuspiciousSignal.NOTIFICATION_LISTENER_DECLARED,
            -> WARNING

            SuspiciousSignal.SIGNING_CERT_CHANGED,
            SuspiciousSignal.DEVICE_ADMIN_NEWLY_ACTIVATED,
            SuspiciousSignal.ACCESSIBILITY_SERVICE_NEWLY_ACTIVATED,
            SuspiciousSignal.VERSION_DOWNGRADED,
            SuspiciousSignal.PERMISSION_ESCALATED,
            -> CRITICAL
        }

        /** `INFO`, wenn [signals] leer ist — dieselbe defensive Default-Haltung wie sonst im
         * Projekt, auch wenn [SuspiciousAppFinding] laut eigenem Klassendoc nie mit leerer
         * Signalmenge erzeugt wird. */
        fun highest(signals: Set<SuspiciousSignal>): ThreatSeverity =
            signals.maxOfOrNull { of(it) } ?: INFO
    }
}
