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
 * Anders als im ConneXias-Framework-Quellprojekt ist [SUITE_PACKAGE_NAMES] hier leer: es gibt
 * keine Geschwister-Suite-APKs mehr zu schützen (kein separates Herald/Sentinel/Steward/
 * Barbican/Atrium/Iris, s. Plan-Context-Abschnitt) — [protectedPackageNames] bleibt trotzdem ein
 * offener Konstruktorparameter, falls künftig doch wieder geschützte Fremdziele dazukommen.
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

        /** Keine Geschwister-Suite-APKs mehr (s. Klassendoc) — leer statt einer festen Liste
         * fremder Paketnamen. */
        val SUITE_PACKAGE_NAMES: Set<String> = emptySet()
    }
}
