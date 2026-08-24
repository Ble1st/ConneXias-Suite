package de.ble1st.warden.appmanagement

import android.content.Context

/**
 * Milestone "weitere Funktionen für den Sicherheitsscanner" (2026-08-22, Feature 3) — flaggt
 * Pakete ohne ermittelbaren Installer (`InstallSourceInfo.getInstallingPackageName() == null`),
 * dem klassischen `adb install`-/Sideload-Merkmal. **Bewusst nur dieses eine, konservative
 * Kriterium** statt einer Allowlist "erwarteter" Installer (z. B. Play Store) — auf diesem
 * Projekt-Testgerät wird auch Warden selbst typischerweise per `adb install` provisioniert (s.
 * CLAUDE.md "On-device verification"), eine Allowlist würde also entweder Warden selbst
 * mit-ausschließen müssen oder für jedes legitime Sideload (z. B. dieser Build-Prozess) ständig
 * anschlagen. `installingPackageName == null` bleibt trotzdem ein sinnvolles, wenn auch
 * schwächeres Signal: Systemapps sind über [de.ble1st.warden.domain.appmanagement
 * .SuspiciousAppScanDecision]s [systemPackageNames]-Ausschluss ohnehin nie betroffen.
 *
 * Pro Paket isoliert: `getInstallSourceInfo` kann für ein zwischenzeitlich deinstalliertes Paket
 * werfen (`NameNotFoundException`) — ein einzelner Fehler bricht den Scan der übrigen Pakete
 * nicht ab, dieselbe "ein fehlgeschlagener Eintrag darf die anderen nicht verhindern"-Haltung wie
 * [de.ble1st.warden.registry.RegistryReconciler].
 */
class InstallSourceScanner(private val context: Context) {

    fun unknownInstallSourcePackageNames(packageNames: Collection<String>): Set<String> =
        packageNames.filterTo(mutableSetOf()) { pkg ->
            runCatching { context.packageManager.getInstallSourceInfo(pkg).installingPackageName }
                .getOrNull() == null
        }
}
