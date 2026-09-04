package de.ble1st.warden.performance

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.util.Log

/** Vordergrund-Nutzungszeit eines Pakets über das jüngste Abfragefenster — **kein** CPU-/RAM-Wert,
 * s. [DeviceMemoryReader]-Klassendoc für die Begründung, warum ein echter Pro-App-Ressourcenwert
 * auf einem nicht gerooteten Gerät nicht beschaffbar ist. Als Aktivitäts-Näherung gedacht: eine
 * App, die kürzlich lange im Vordergrund lief, hat mutmaßlich auch mehr CPU/RAM/Akku verbraucht
 * als eine, die es nicht tat — eine Korrelation, kein Messwert. */
data class AppUsageInfo(val packageName: String, val totalForegroundTimeMillis: Long)

/**
 * Performance-Monitoring-Fenster (2026-08-25). `UsageStatsManager` statt
 * `ActivityManager.getRunningAppProcesses()` (s. [DeviceMemoryReader]-Klassendoc) — der einzige
 * öffentliche API-Pfad, der für **fremde** Pakete überhaupt eine Aktivitäts-Kennzahl liefert, aber
 * nur nach einer einmaligen manuellen Nutzer-Freigabe ("Nutzungsdatenzugriff") unter
 * Einstellungen. **Auch ein Device Owner bekommt diese Freigabe nicht automatisch/still** — anders
 * als z. B. `POST_NOTIFICATIONS` (`DevicePolicyManager.setPermissionGrantState`, s.
 * `WardenApplication`-Klassendoc) ist `PACKAGE_USAGE_STATS` kein normales Laufzeit-Recht, sondern
 * eine AppOps-Sonderberechtigung ohne DPM-Silent-Grant-API — [hasAccess] prüft deshalb, statt es
 * einfach zu versuchen, und [usageAccessSettingsIntent] öffnet den nötigen Einstellungen-Screen.
 */
class AppUsageReader(private val context: Context) {

    fun hasAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** `null`, wenn [hasAccess] `false` ist oder `UsageStatsManager` nicht verfügbar — der
     * Aufrufer zeigt dafür den Freigabe-Hinweis statt einer leeren/falschen Liste. [windowHours]
     * Stunden rückwirkend ab jetzt. */
    fun recentForegroundUsage(windowHours: Long = 24): List<AppUsageInfo>? {
        if (!hasAccess()) return null
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val begin = end - windowHours * 3_600_000L
        return try {
            usm.queryAndAggregateUsageStats(begin, end)
                .values
                .filter { it.totalTimeInForeground > 0 }
                .map { AppUsageInfo(it.packageName, it.totalTimeInForeground) }
                .sortedByDescending { it.totalForegroundTimeMillis }
        } catch (e: SecurityException) {
            Log.w(TAG, "queryAndAggregateUsageStats blocked despite hasAccess() == true", e)
            null
        }
    }

    private companion object {
        const val TAG = "AppUsageReader"
    }
}
