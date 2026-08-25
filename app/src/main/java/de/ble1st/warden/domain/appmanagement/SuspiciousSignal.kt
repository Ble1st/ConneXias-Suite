package de.ble1st.warden.domain.appmanagement

/**
 * Milestone "Automatisches Einfrieren verdächtiger Apps" (2026-08-20, auf Nutzerwunsch: "z. B.
 * App fordert Admin oder blockiert über Accessibility den Bildschirm"), seit "Manifest-Scan +
 * Sofort-Benachrichtigung" (2026-08-21) **manifest-basiert statt zustandsbasiert** — die beiden
 * konkreten Verdachtssignale, die [SuspiciousAppScanDecision] auswertet, erkennen jetzt bereits
 * die reine *Fähigkeit* (im Manifest deklariert, s. [DeviceAdminCapabilityScanner]/
 * [de.ble1st.warden.appmanagement.AccessibilityServiceScanner]-Klassendoc für die Begründung),
 * nicht erst die vom Nutzer bereits erteilte Aktivierung. Beide sind bekannte Maschen echter
 * Android-Malware (Ransomware/Banking-Trojaner): Device-Admin-Rechte erschweren die eigene
 * Deinstallation und erlauben der bösartigen App selbst destruktive DPM-Aufrufe; ein aktivierter
 * Accessibility-Service kann Touch-Events simulieren, Bildschirminhalte lesen und z. B.
 * Deinstallations-/Berechtigungsdialoge automatisch wegklicken oder den Bildschirm mit einem
 * Overlay blockieren.
 *
 * [toBitmask]/[fromBitmask] sind die Ordinal-über-Int-Konvention für den Bus-Transport
 * (`de.ble1st.warden.appmanagement.SuspiciousAppFindingInfo.signalsBitmask`) — dieselbe
 * hier als Bitmaske, weil ein Fund mehrere Signale gleichzeitig tragen kann.
 *
 * Sechs weitere, ebenfalls über [SuspiciousAppScanDecision.evaluate] eingebrachte Signale
 * (2026-08-22, auf Nutzerwunsch "weitere Funktionen für den Sicherheitsscanner"):
 * - [OVERLAY_PERMISSION_DECLARED]/[NOTIFICATION_LISTENER_DECLARED] — dieselbe
 *   "Fähigkeit statt Aktivierung"-Haltung wie [EXTRA_DEVICE_ADMIN]/
 *   [ACCESSIBILITY_SERVICE_DECLARED]: `SYSTEM_ALERT_WINDOW` (Overlay-/Tapjacking-Vektor) bzw.
 *   `BIND_NOTIFICATION_LISTENER_SERVICE` (kann Benachrichtigungsinhalte, z. B. 2FA-Codes,
 *   mitlesen) werden schon bei bloßer Deklaration erkannt.
 * - [UNKNOWN_INSTALL_SOURCE] — kein ermittelbarer Installer (`installingPackageName == null`,
 *   klassisches `adb install`-/Sideload-Merkmal, s.
 *   `de.ble1st.warden.appmanagement.InstallSourceScanner`).
 * - [SIGNING_CERT_CHANGED] — das Signatur-Zertifikat eines bereits vorher gesehenen Pakets hat
 *   sich zwischen zwei Scans geändert (Update-Hijacking-Signal, s.
 *   `de.ble1st.warden.domain.appmanagement.SigningCertChangeDecision`).
 * - [DEVICE_ADMIN_NEWLY_ACTIVATED]/[ACCESSIBILITY_SERVICE_NEWLY_ACTIVATED] — ein Paket, dessen
 *   Geräteadmin/Bedienungshilfen-Dienst zwischen zwei Scans von inaktiv auf aktiv gewechselt ist
 *   (dringlicher als die reine Deklaration, s.
 *   `de.ble1st.warden.domain.appmanagement.ActivationTransitionDecision`).
 *
 * Ein neuntes Signal (2026-08-25, "LockMode/Threat-Protection-Ausbau", angelehnt an
 * Feature-Ideenliste Punkt 50 "Rollback-Schutz"):
 * - [VERSION_DOWNGRADED] — `versionCode` eines bereits vorher gesehenen Pakets ist zwischen zwei
 *   Scans gesunken (Downgrade-/Rollback-Angriff: eine ältere, mutmaßlich verwundbare oder bereits
 *   als bösartig bekannte Version wurde über die vorherige installiert), s.
 *   `de.ble1st.warden.appmanagement.VersionHistoryStore`/
 *   `de.ble1st.warden.domain.appmanagement.VersionDowngradeDecision`. **Kein** Zugriff auf
 *   Androids systemeigenen Play-Install-Rollback-Schutz (API 9+) — der ist Teil des
 *   Play-Installers/-Signaturschemas v3 und für Drittapps (auch Device Owner) nicht ansprechbar;
 *   dies ist eine eigene, lokale versionCode-Baseline nach demselben Muster wie
 *   [SIGNING_CERT_CHANGED].
 */
enum class SuspiciousSignal(val bit: Int) {
    EXTRA_DEVICE_ADMIN(1 shl 0),
    ACCESSIBILITY_SERVICE_DECLARED(1 shl 1),
    OVERLAY_PERMISSION_DECLARED(1 shl 2),
    NOTIFICATION_LISTENER_DECLARED(1 shl 3),
    UNKNOWN_INSTALL_SOURCE(1 shl 4),
    SIGNING_CERT_CHANGED(1 shl 5),
    DEVICE_ADMIN_NEWLY_ACTIVATED(1 shl 6),
    ACCESSIBILITY_SERVICE_NEWLY_ACTIVATED(1 shl 7),
    VERSION_DOWNGRADED(1 shl 8),
    ;

    companion object {
        fun toBitmask(signals: Set<SuspiciousSignal>): Int = signals.fold(0) { acc, signal -> acc or signal.bit }

        fun fromBitmask(bitmask: Int): Set<SuspiciousSignal> =
            entries.filterTo(mutableSetOf()) { (bitmask and it.bit) != 0 }
    }
}
