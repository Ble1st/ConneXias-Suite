package de.ble1st.warden.domain.appmanagement

/**
 * Milestone "App-Verwaltung: Einfrieren/Deaktivieren" (2026-08-20, auf Nutzerwunsch: "Warden hat
 * ohnehin QUERY_ALL_PACKAGES [seit Milestone I.4] — dieselbe Sichtbarkeit reicht auch, um
 * Fremd-Apps über den Device-Owner-Pfad einzufrieren"). Reine, framework-freie Schutzprüfung,
 * strukturell erzwungen wie [de.ble1st.warden.domain.bus.CapabilityMatrix]s
 * `NEVER_ON_BUS`: Warden selbst und jede bekannte Suite-APK dürfen nie eingefroren werden — ein
 * eingefrorenes Warden wäre selbst nicht mehr per Device-Owner-Pfad ansprechbar (kein
 * offensichtlicher Rückweg außer Werksreset, dieselbe Risikokategorie wie die ursprünglichen
 * C.5-Bündel-Mitglieder), eine eingefrorene Suite-APK würde ihren jeweiligen Sicherheitszweck
 * (Sentinel-PIN, Herald-Fernbedienung, Barbican-Sinkhole, Stewards Uninstall-Schutz-Ziel …)
 * unbrauchbar machen, ohne dass ein naheliegender Rückweg bliebe.
 *
 * [AppFreezeGuard.isProtected] wird in [de.ble1st.warden.appmanagement.AppManagementController]
 * *vor* jedem `setFrozen()`-Aufruf geprüft — der
 * eigentliche `DevicePolicyManager`-Aufruf läuft nie für ein geschütztes Ziel, unabhängig davon,
 * was eine künftige UI (Herald) anbietet oder verbietet (zwei unabhängige Schichten, dasselbe
 * Muster wie überall sonst im Projekt).
 */
object AppFreezeGuard {

    fun isProtected(targetPackageName: String, ownPackageName: String, suitePackageNames: Set<String>): Boolean =
        targetPackageName == ownPackageName || targetPackageName in suitePackageNames
}
