package de.ble1st.warden.appmanagement

import android.content.Context
import android.util.Log
import de.ble1st.warden.domain.appmanagement.AppFreezeGuard
import de.ble1st.warden.wardenAuditLog

/**
 * Milestone "App-Verwaltung: Einfrieren/Deaktivieren" (Konzept: Warden hat ohnehin
 * `QUERY_ALL_PACKAGES` — dieselbe Sichtbarkeit soll auch reichen, um Apps zu deaktivieren/
 * einzufrieren). App-weit in [de.ble1st.warden.WardenApplication] gehaltener Controller — kein
 * `Safeguard`-Registry-Eintrag, s. [AppFreezeManager]-Klassendoc.
 *
 * [ownPackageName]/[protectedPackageNames] verhindern strukturell, dass Warden sich selbst
 * einfriert — geprüft über [AppFreezeGuard] (pure/testbar), *bevor* [AppFreezeManager] überhaupt
 * angefasst wird. Dieselbe "strukturell erzwungen, nicht nur dokumentiert"-Haltung wie
 * `CapabilityMatrix.NEVER_ON_BUS`.
 *
 * **Seit "Sentinel: eigenständige Kiosk-PIN-App" (2026-08-26) wieder nicht mehr leer:**
 * [SUITE_PACKAGE_NAMES] schützt `de.ble1st.warden.sentinel` — ein eingefrorenes Sentinel wäre für
 * Lock-Task nicht mehr startbar (`ActivityNotFoundException`/stiller Kiosk-Ausfall bei der
 * nächsten Scharfschaltung), ohne dass ein naheliegender Rückweg bliebe, dieselbe Risikokategorie
 * wie Warden selbst. Die Deinstallation ist separat über [de.ble1st.warden.registry
 * .SentinelUninstallProtectionSafeguard] (`DevicePolicyManager.setUninstallBlocked`, wirkt auch
 * gegen Einstellungen/`pm uninstall`, nicht nur gegen Wardens eigene UI-Pfade hier) blockiert —
 * zwei unabhängige Schichten für zwei unabhängige Angriffsflächen.
 *
 * **Um die drei restlichen Suite-Apps erweitert (analyse.md, 2026-09-02, Mittel):** vorher deckte
 * [SUITE_PACKAGE_NAMES] nur Wardens eigenes Sentinel-Modul ab — Warden konnte ConneXias
 * Kamera/Files/Galerie über genau diesen Bildschirm ohne jede Rückfrage einfrieren, obwohl die
 * Suite sie als zusammengehörig bewirbt. Anders als bei Sentinel gibt es hier keinen zweiten,
 * unabhängigen Deinstallationsschutz (kein `setUninstallBlocked` für Fremd-Apps, die Warden nicht
 * selbst installiert hat) — dieser Freeze-Schutz ist für die drei der einzige. Bewusst weiterhin
 * String-Literale statt eines Imports (die drei Module sind für `:app` nicht einmal als Asset
 * gebündelt, ganz unabhängige APKs — noch weniger Kopplung als beim gebündelten Sentinel).
 */
class AppManagementController(
    private val context: Context,
    private val appLister: InstalledAppLister,
    private val freezeManager: AppFreezeManager,
    private val ownPackageName: String,
    private val protectedPackageNames: Set<String>,
) {

    private val logStore by lazy { wardenAuditLog(context) }

    fun listApps(): List<AppManagementInfo> =
        appLister.listInstalledApps().map { entry ->
            AppManagementInfo(
                packageName = entry.packageName,
                label = entry.label,
                isSystemApp = entry.isSystemApp,
                frozen = freezeManager.isFrozen(entry.packageName),
                protected = isProtected(entry.packageName),
            )
        }

    fun isFrozen(packageName: String): Boolean = freezeManager.isFrozen(packageName)

    fun setFrozen(packageName: String, frozen: Boolean): Boolean {
        if (isProtected(packageName)) {
            logStore.append(
                Log.WARN,
                TAG,
                "Einfrieren abgelehnt (geschütztes Ziel): pkg=$packageName frozen=$frozen",
            )
            return false
        }
        val result = freezeManager.setFrozen(packageName, frozen)
        logStore.append(
            if (result) Log.WARN else Log.ERROR,
            TAG,
            "App ${if (frozen) "eingefroren" else "entfroren"}: pkg=$packageName success=$result",
        )
        return result
    }

    private fun isProtected(packageName: String): Boolean =
        AppFreezeGuard.isProtected(packageName, ownPackageName, protectedPackageNames)

    companion object {
        private const val TAG = "AppManagement"

        /** Paketnamen als String-Literale statt per Import — bewusst kein Compile-Zeit-Bezug von
         * `:app`s `appmanagement`-Paket auf `:sentinel` (das Modul existiert für `:app` ohnehin
         * nur als gebündeltes Asset, s. `SentinelSilentInstaller`-Klassendoc) oder auf die drei
         * völlig eigenständigen Suite-APKs (nicht einmal im selben Repo-Build-Graphen). Dieselbe
         * "String-Literal statt Import"-Begründung wie in [de.ble1st.warden.registry
         * .WardenLockTaskAuthorizer]. */
        val SUITE_PACKAGE_NAMES: Set<String> = setOf(
            "de.ble1st.warden.sentinel",
            "de.ble1st.camera",
            "de.ble1st.files",
            "de.ble1st.gallery",
        )
    }
}
