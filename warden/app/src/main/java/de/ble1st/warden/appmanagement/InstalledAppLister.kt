package de.ble1st.warden.appmanagement

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** One row for Herald's firewall/app-management lists (labels resolved in Warden — Herald has no
 * visibility). [isSystemApp] backs the "Systemapps anzeigen"-Filter (Milestone "App-Verwaltung:
 * Einfrieren", 2026-08-20) shared by Herald's firewall and app-management screens — a system app
 * is one carrying [ApplicationInfo.FLAG_SYSTEM] (shipped with the OS) or
 * [ApplicationInfo.FLAG_UPDATED_SYSTEM_APP] (a system app updated via Play/Silent-Update, still
 * effectively a system component). */
data class InstalledAppEntry(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
)

/**
 * Milestone I.4: enumerates all installed applications for the per-app firewall UI. Seit Milestone
 * "App-Verwaltung: Einfrieren" (2026-08-20) auch für Heralds App-Verwaltungs-Liste wiederverwendet
 * — dieselbe Enumeration, kein zweiter Lesepfad.
 *
 * Requires [android.permission.QUERY_ALL_PACKAGES] in the **host APK** manifest (Warden only —
 * private DO device, NetGuard-like full list). Without it, PackageManager returns an incomplete set
 * on Android 11+.
 *
 * **Empirischer Fund + Fix (2026-08-20, real auf dem A15 beim Live-Test der frisch gemergten
 * "App-Verwaltung: Einfrieren"-Funktion gefunden):** ein per [android.app.admin.DevicePolicyManager
 * .setApplicationHidden] eingefrorenes Fremdpaket verschwand nach dem Einfrieren **vollständig**
 * aus dieser Liste (`getInstalledApplications` mit Flags=0 liefert eingefrorene Apps nicht mehr
 * zurück, dieselbe interne Sichtbarkeitsregel wie bei einer per `DELETE_KEEP_DATA` deinstallierten
 * App) — real bestätigt per `dumpsys package` (`hidden=true` blieb bestehen) und `pm list packages
 * -u` (zeigte das Paket, `pm list packages` ohne `-u` nicht). Für Heralds App-Verwaltungs-Liste ein
 * echter, funktionsblockierender Bug: eine eingefrorene App ließ sich über die UI nie wieder
 * auftauen, weil ihre Zeile schlicht nicht mehr gerendert wurde. Fix: [PackageManager
 * .MATCH_UNINSTALLED_PACKAGES] macht eingefrorene Apps wieder sichtbar — filtert aber zusätzlich
 * auf `sourceDir != null`, um echte (per Nutzer-Deinstallation mit "Daten behalten")
 * daten-only-Karteileichen ohne vorhandene APK-Datei nicht als Geisterzeilen mitzulisten, die
 * `DevicePolicyManager.setApplicationHidden` ohnehin nur wirkungslos anfassen könnte.
 */
class InstalledAppLister(context: Context) {

    private val packageManager = context.packageManager

    fun listInstalledApps(): List<InstalledAppEntry> =
        packageManager
            .getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()))
            .filter { info -> info.sourceDir != null }
            .map { info -> entryFor(info) }
            .sortedBy { it.label.lowercase() }

    private fun entryFor(info: ApplicationInfo): InstalledAppEntry =
        InstalledAppEntry(
            packageName = info.packageName,
            label = packageManager.getApplicationLabel(info).toString().ifBlank { info.packageName },
            isSystemApp = (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
        )
}
