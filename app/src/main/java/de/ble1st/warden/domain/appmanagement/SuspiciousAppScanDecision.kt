package de.ble1st.warden.domain.appmanagement

/**
 * Milestone "Automatisches Einfrieren verdächtiger Apps" (2026-08-20) — reine, framework-freie
 * Entscheidungslogik, dieselbe Decision/Executor-Trennung wie überall sonst im Projekt
 * (`FailsafeDecision`, `SentinelPinDecision`, …): [evaluate] bekommt nur schon eingesammelte
 * Rohdaten (Paketnamen-Mengen) und produziert eine reine Funde-Liste, ohne selbst
 * `DevicePolicyManager`/`AccessibilityManager`/`PackageManager` zu kennen — die Android-Anbindung
 * lebt im `appmanagement`-Package ([de.ble1st.warden.appmanagement.DeviceAdminCapabilityScanner]/
 * [de.ble1st.warden.appmanagement.AccessibilityServiceScanner]).
 *
 * Vier unabhängige Ausschlussmengen, alle *vor* jeder Funde-Erzeugung geprüft (dieselbe
 * "strukturell erzwungen"-Haltung wie `CapabilityMatrix.NEVER_ON_BUS`/`AppFreezeGuard`):
 * [ownPackageName] (Warden selbst — ist ohnehin immer sein eigener Device-Admin, das wäre sonst
 * ein garantierter Falsch-Fund bei *jedem* Scan), [protectedPackageNames] (bekannte Suite-APKs,
 * dieselbe Menge wie [de.ble1st.warden.domain.appmanagement.AppFreezeGuard]),
 * [systemPackageNames] (vorinstallierte Systemapps — z. B. ein herstellereigener
 * Barrierefreiheits-Dienst soll nicht als Verdachtsfall behandelt werden, das eigentliche
 * Bedrohungsmodell betrifft nachträglich installierte Fremd-Apps) und [trustedPackageNames] (vom
 * Nutzer bewusst als unbedenklich markiert, s. `SuspiciousAppScanStore`).
 *
 * Sechs weitere Signal-Paket-Mengen (2026-08-22, auf Nutzerwunsch "weitere Funktionen für den
 * Sicherheitsscanner", s. [SuspiciousSignal]-Klassendoc für die Begründung jedes einzelnen), alle
 * mit Default `emptySet()` — bestehende Aufrufer mit nur den ursprünglichen zwei Signalen bleiben
 * unverändert kompilierbar. Eine siebte (2026-08-25, [SuspiciousSignal.VERSION_DOWNGRADED]),
 * derselbe Default.
 */
object SuspiciousAppScanDecision {

    fun evaluate(
        deviceAdminPackageNames: Set<String>,
        accessibilityPackageNames: Set<String>,
        overlayPackageNames: Set<String> = emptySet(),
        notificationListenerPackageNames: Set<String> = emptySet(),
        unknownInstallSourcePackageNames: Set<String> = emptySet(),
        signingCertChangedPackageNames: Set<String> = emptySet(),
        deviceAdminNewlyActivatedPackageNames: Set<String> = emptySet(),
        accessibilityNewlyActivatedPackageNames: Set<String> = emptySet(),
        versionDowngradedPackageNames: Set<String> = emptySet(),
        ownPackageName: String,
        protectedPackageNames: Set<String>,
        systemPackageNames: Set<String>,
        trustedPackageNames: Set<String>,
    ): List<SuspiciousAppFinding> {
        val excluded = protectedPackageNames + ownPackageName + systemPackageNames + trustedPackageNames
        val signalsByPackage = linkedMapOf<String, MutableSet<SuspiciousSignal>>()

        fun record(packageNames: Set<String>, signal: SuspiciousSignal) {
            for (pkg in packageNames) {
                if (pkg in excluded) continue
                signalsByPackage.getOrPut(pkg) { mutableSetOf() }.add(signal)
            }
        }

        record(deviceAdminPackageNames, SuspiciousSignal.EXTRA_DEVICE_ADMIN)
        record(accessibilityPackageNames, SuspiciousSignal.ACCESSIBILITY_SERVICE_DECLARED)
        record(overlayPackageNames, SuspiciousSignal.OVERLAY_PERMISSION_DECLARED)
        record(notificationListenerPackageNames, SuspiciousSignal.NOTIFICATION_LISTENER_DECLARED)
        record(unknownInstallSourcePackageNames, SuspiciousSignal.UNKNOWN_INSTALL_SOURCE)
        record(signingCertChangedPackageNames, SuspiciousSignal.SIGNING_CERT_CHANGED)
        record(deviceAdminNewlyActivatedPackageNames, SuspiciousSignal.DEVICE_ADMIN_NEWLY_ACTIVATED)
        record(accessibilityNewlyActivatedPackageNames, SuspiciousSignal.ACCESSIBILITY_SERVICE_NEWLY_ACTIVATED)
        record(versionDowngradedPackageNames, SuspiciousSignal.VERSION_DOWNGRADED)

        return signalsByPackage
            .map { (pkg, signals) -> SuspiciousAppFinding(pkg, signals) }
            .sortedBy { it.packageName }
    }
}
